#!/usr/bin/env python3
"""AgentKit 测试用 mock OpenAI 端点（第三版，覆盖异常/长内容/交互场景）。

在 mock_llm2.py 基础上扩展，保留原有 calc / err401 / 回显行为。

触发词（只看「用户当前请求：」之后的真正用户输入，避免被设备上下文里的通知污染）：
  calc        → calculator 工具调用（原有）
  err401      → 401 无效 Key
  err429      → 429 限流
  err500      → 500 服务器错误
  hang        → 永不响应（测超时）
  long        → 8000 字长回复（测滚动/性能）
  md          → 复杂 Markdown（代码块/表格/长链接/嵌套列表）
  empty       → 空回复
  slow        → 慢速流式（每 chunk 0.4s，共 ~40s，测停止按钮）
  bigtool     → shell_run 跑 seq 1 2000（测审批 + 大结果渲染）
  askuser     → ask_user 工具（选项卡片）
  plan        → plan_propose 工具
"""
import json, time, sys, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = '/tmp/mock_llm.log'


def log(s):
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(s + '\n')


LONG_TEXT = ''.join(
    f'第{i}段：这是一段用于测试长文本渲染的占位内容，包含中文、English、数字 12345 与标点。\n'
    for i in range(1, 201)
)

MD_TEXT = """# 一级标题

这是一段正文，包含**加粗**、*斜体*、`行内代码` 和一个[链接](https://example.com/a/very/long/path/that/should/not/break/layout/even/when/it/is/very/long?q=1&b=2)。

## 二级标题

```kotlin
fun main() {
    val list = listOf(1, 2, 3)
    println(list.map { it * 2 })
}
```

| 列一 | 列二 | 列三 |
|---|---|---|
| 很长的单元格内容 AAAAAAAA | 短 | 中等长度内容 |
| 1 | 2 | 3 |

- 第一层
  - 第二层
    - 第三层
- 回到第一层

1. 有序一
2. 有序二

> 引用块：这里是一段比较长的引用文字，用来测试左边框在不同行高下是否对齐。

---

超长不换行串：AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA

结束。
"""


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def sse(self, obj):
        self.wfile.write(('data: ' + json.dumps(obj, ensure_ascii=False) + '\n\n').encode())
        self.wfile.flush()

    def chunk(self, delta, finish=None):
        return {'id': 'chatcmpl-mock', 'object': 'chat.completion.chunk',
                'created': int(time.time()), 'model': 'mock-model',
                'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}

    def do_GET(self):
        b = json.dumps({'object': 'list', 'data': [{'id': 'mock-model'}]}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def err(self, code, message, typ='invalid_request_error', err_code=''):
        b = json.dumps({'error': {'message': message, 'type': typ, 'code': err_code}},
                       ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        n = int(self.headers.get('content-length') or 0)
        try:
            req = json.loads(self.rfile.read(n) or b'{}')
        except Exception:
            req = {}
        msgs = req.get('messages') or []
        roles = [m.get('role') for m in msgs]
        last = ''
        for m in reversed(msgs):
            if m.get('role') == 'user':
                last = str(m.get('content') or '')
                break
        # 设备上下文会把最近通知拼进来，里面可能含关键词——只看真正的用户请求段
        req_text = last.split('用户当前请求：')[-1] if '用户当前请求：' in last else last
        t = (req_text or '').lower().strip()
        tool_msgs = [m for m in msgs if m.get('role') == 'tool']
        log('REQ roles=%s tools=%d stream=%s toolres=%d last=%r' % (
            roles, len(req.get('tools') or []), req.get('stream'), len(tool_msgs), last[:80]))

        # 第二轮：带着工具结果回来 → 给最终答复
        last_user_idx = max((i for i, m in enumerate(msgs) if m.get('role') == 'user'), default=-1)
        recent_tool = [m for m in msgs[last_user_idx + 1:] if m.get('role') == 'tool']
        if recent_tool:
            content = str(recent_tool[-1].get('content') or '')
            # 大结果只回显前后各 200 字，避免二次放大
            if len(content) > 400:
                content = content[:200] + '\n…（中间省略）…\n' + content[-200:]
            self.reply_text(req, '工具执行完毕，结果是：' + content[:1500])
            return

        # ---- 错误码场景 ----
        if 'err401' in t:
            self.err(401, 'Incorrect API key provided', 'invalid_request_error', 'invalid_api_key')
            return
        if 'err429' in t:
            self.err(429, 'Rate limit reached for requests', 'requests', 'rate_limit_exceeded')
            return
        if 'err500' in t:
            self.err(500, 'The server had an error while processing your request', 'server_error', '')
            return

        # 永不响应（测超时）
        if t == 'hang':
            log('HANG: sleeping 600s')
            time.sleep(600)
            return

        # 工具调用：shell_run（大结果，且会触发审批）
        if 'bigtool' in t:
            args = json.dumps({'command': 'seq 1 2000'})
            self.tool_call(req, 'shell_run', args)
            return

        # 工具调用：ask_user
        if 'askuser' in t:
            args = json.dumps({'question': '要清理哪个目录？',
                               'options': 'Download,Temp,Cache'}, ensure_ascii=False)
            self.tool_call(req, 'ask_user', args)
            return

        # 工具调用：plan_propose
        if t == 'plan':
            args = json.dumps({'goal': '整理下载目录',
                               'steps': '1. 列出文件\n2. 按类型分组\n3. 移动到对应目录'},
                              ensure_ascii=False)
            self.tool_call(req, 'plan_propose', args)
            return

        # 工具调用：calculator（原有）
        if 'calc' in t:
            self.tool_call(req, 'calculator', '{"expression":"(120+80)*0.85"}')
            return

        # 长文本
        if t == 'long':
            self.reply_text(req, LONG_TEXT)
            return

        # 复杂 markdown
        if t == 'md':
            self.reply_text(req, MD_TEXT)
            return

        # 空回复
        if t == 'empty':
            self.reply_text(req, '')
            return

        # 慢速流式（测停止按钮）
        if t == 'slow':
            self.slow_stream(req)
            return

        self.reply_text(req, '你好，我是模拟模型。你说：' + (last or '(空)'))

    def tool_call(self, req, name, args):
        """返回一次 tool_calls（流式分三片）。"""
        if req.get('stream'):
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.sse(self.chunk({'role': 'assistant', 'content': None, 'tool_calls': [
                {'index': 0, 'id': 'call_mock_1', 'type': 'function',
                 'function': {'name': name, 'arguments': ''}}]}))
            time.sleep(0.05)
            # 分两片发参数，验证 App 的增量拼接
            half = len(args) // 2
            self.sse(self.chunk({'tool_calls': [
                {'index': 0, 'function': {'arguments': args[:half]}}]}))
            time.sleep(0.05)
            self.sse(self.chunk({'tool_calls': [
                {'index': 0, 'function': {'arguments': args[half:]}}]}))
            time.sleep(0.05)
            self.sse(self.chunk({}, 'tool_calls'))
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        else:
            b = json.dumps({'id': 'chatcmpl-mock', 'object': 'chat.completion',
                            'created': int(time.time()), 'model': 'mock-model',
                            'choices': [{'index': 0,
                                         'message': {'role': 'assistant', 'content': None,
                                                     'tool_calls': [{'id': 'call_mock_1',
                                                                     'type': 'function',
                                                                     'function': {'name': name,
                                                                                  'arguments': args}}]},
                                         'finish_reason': 'tool_calls'}],
                            'usage': {'prompt_tokens': 10, 'completion_tokens': 10,
                                      'total_tokens': 20}},
                           ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(b)))
            self.end_headers()
            self.wfile.write(b)

    def slow_stream(self, req):
        """慢速流式：100 片 × 0.4s ≈ 40 秒，用于测「停止生成」。"""
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'close')
        self.end_headers()
        for i in range(100):
            try:
                self.sse(self.chunk({'content': f'第{i + 1}片。'}))
            except (BrokenPipeError, ConnectionResetError):
                log('CLIENT DISCONNECTED at chunk %d（停止按钮生效）' % (i + 1))
                return
            time.sleep(0.4)
        self.sse(self.chunk({}, 'stop'))
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()

    def reply_text(self, req, text):
        if req.get('stream'):
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'close')
            self.end_headers()
            for ch in [text[i:i + 12] for i in range(0, len(text), 12)]:
                try:
                    self.sse(self.chunk({'content': ch}))
                except (BrokenPipeError, ConnectionResetError):
                    log('CLIENT DISCONNECTED during reply_text')
                    return
                time.sleep(0.02)
            self.sse(self.chunk({}, 'stop'))
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        else:
            b = json.dumps({'id': 'chatcmpl-mock', 'object': 'chat.completion',
                            'created': int(time.time()), 'model': 'mock-model',
                            'choices': [{'index': 0,
                                         'message': {'role': 'assistant', 'content': text},
                                         'finish_reason': 'stop'}],
                            'usage': {'prompt_tokens': 10, 'completion_tokens': 10,
                                      'total_tokens': 20}},
                           ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(b)))
            self.end_headers()
            self.wfile.write(b)


def _daemonize():
    if os.fork() > 0:
        os._exit(0)
    os.setsid()
    if os.fork() > 0:
        os._exit(0)
    fd = os.open('/tmp/mock.out', os.O_WRONLY | os.O_CREAT | os.O_TRUNC)
    os.dup2(fd, 1)
    os.dup2(fd, 2)
    os.open('/dev/null', os.O_RDONLY)


if __name__ == '__main__':
    if '--daemon' in sys.argv:
        _daemonize()
    ThreadingHTTPServer(('0.0.0.0', 8080), H).serve_forever()
