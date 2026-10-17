package com.example.agentkit.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * 无界面的网页操作（W-15）：打开 / 快照 / 点击 / 输入 / 执行 JS / 截图。
 *
 * 设计要点：
 *  - 所有 WebView 调用都切主线程（WebView 不是线程安全的）；
 *  - 页面加载用 `onPageFinished` + 超时（默认 20s），超时也返回「已尽力加载」的快照，
 *    而不是直接失败——很多页面永远不会触发 finished（长连接/广告）；
 *  - 快照输出**面向模型**：标题 + URL + 可交互元素编号列表 + 正文摘要（截断），
 *    而不是整页 HTML（会把上下文预算瞬间吃光）。
 */
class WebViewBrowser {

    /** 打开 URL 并等待加载完成（或超时）。返回最终 URL。 */
    suspend fun open(url: String, timeoutMs: Long = 20_000): Result<String> {
        val wv = BrowserHost.webView() ?: return Result.failure(IllegalStateException(NOT_READY))
        val deferred = CompletableDeferred<Unit>()
        BrowserHost.onPageFinished = { if (!deferred.isCompleted) deferred.complete(Unit) }
        return withContext(Dispatchers.Main) {
            runCatching {
                wv.loadUrl(url)
                withTimeoutOrNull(timeoutMs) { deferred.await() }
                wv.url ?: url
            }.onFailure { BrowserHost.onPageFinished = null }
        }
    }

    /** 页面前进/后退（可选能力，工具暂未暴露）。 */
    suspend fun back(): Boolean = withContext(Dispatchers.Main) {
        val wv = BrowserHost.webView() ?: return@withContext false
        if (wv.canGoBack()) { wv.goBack(); true } else false
    }

    /** 执行 JS，返回字符串结果（null → 空串）。 */
    suspend fun eval(script: String): Result<String> {
        val wv = BrowserHost.webView() ?: return Result.failure(IllegalStateException(NOT_READY))
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    wv.evaluateJavascript(script) { raw ->
                        if (cont.isActive) cont.resume(decodeJsResult(raw))
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(Result.failure<String>(it))
                }
            }
        }
    }

    /** DOM 快照：标题 + URL + 可交互元素 + 正文摘要。 */
    suspend fun snapshot(): Result<String> = eval(SNAPSHOT_JS)

    /** 点击：优先 CSS 选择器，失败后按可见文本匹配。 */
    suspend fun click(selectorOrText: String): Result<String> = eval(
        CLICK_JS.replace("__TARGET__", JsonPrimitive(selectorOrText).toString())
    )

    /** 输入：设置 value 并派发 input/change 事件（React 受控组件也能收到）。 */
    suspend fun type(selector: String, text: String): Result<String> = eval(
        TYPE_JS
            .replace("__SEL__", JsonPrimitive(selector).toString())
            .replace("__TEXT__", JsonPrimitive(text).toString())
    )

    /** 截图（WebView 内容，非整屏）。 */
    suspend fun screenshot(): Result<Bitmap> = withContext(Dispatchers.Main) {
        val wv = BrowserHost.webView() ?: return@withContext Result.failure(IllegalStateException(NOT_READY))
        runCatching {
            val w = wv.width.takeIf { it > 0 } ?: 1080
            val h = wv.height.takeIf { it > 0 } ?: 1920
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bmp))
            bmp
        }
    }

    companion object {
        const val NOT_READY = "后台浏览器不可用（App 需先打开一次，且不要被系统回收）"

        private val main = Handler(Looper.getMainLooper())

        /**
         * evaluateJavascript 的返回值是「JSON 编码的字符串」，例如 `"\"hello\""`、`null`、`123`。
         * 这里做最小解码：去引号 + 反转义常见字符；非字符串原样返回。
         */
        internal fun decodeJsResult(raw: String?): Result<String> {
            if (raw == null) return Result.success("")
            val t = raw.trim()
            if (t == "null" || t.isEmpty()) return Result.success("")
            if (!t.startsWith("\"")) return Result.success(t)
            val body = t.removeSurrounding("\"")
            val sb = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val n = body[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'u' -> {
                            if (i + 5 < body.length) {
                                val hex = body.substring(i + 2, i + 6)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 6
                            } else { sb.append(n); i += 2 }
                        }
                        else -> { sb.append(n); i += 2 }
                    }
                } else {
                    sb.append(c); i++
                }
            }
            return Result.success(sb.toString())
        }

        /** 快照脚本：输出紧凑文本，元素上限 80 个、正文 1200 字。 */
        private val SNAPSHOT_JS = """
            (function(){
              function vis(e){
                var r=e.getBoundingClientRect();
                var s=getComputedStyle(e);
                return r.width>2 && r.height>2 && s.visibility!=='hidden' && s.display!=='none';
              }
              var out=[]; var i=0;
              var els=document.querySelectorAll('a,button,input,textarea,select,[role=button],[onclick]');
              for (var k=0;k<els.length && i<80;k++){
                var e=els[k];
                if(!vis(e)) continue;
                var t=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,60);
                var tag=e.tagName.toLowerCase();
                var extra='';
                if(e.id) extra+=' #'+e.id;
                if(e.name) extra+=' [name='+e.name+']';
                if(e.tagName==='INPUT') extra+=' ('+(e.type||'text')+')';
                out.push((++i)+'. <'+tag+'> '+(t||'(无文字)')+extra);
              }
              var body=(document.body?document.body.innerText:'').replace(/\s+/g,' ').trim().slice(0,1200);
              return '标题: '+document.title+'\nURL: '+location.href+'\n\n可交互元素('+out.length+'):\n'+(out.join('\n')||'(无)')+'\n\n正文摘要:\n'+(body||'(空)');
            })()
        """.trimIndent()

        /** 点击脚本：先 querySelector，再按文本精确/包含匹配。 */
        private val CLICK_JS = """
            (function(target){
              var e=null;
              try { e=document.querySelector(target); } catch(err) { e=null; }
              if(!e){
                var all=Array.prototype.slice.call(document.querySelectorAll('a,button,[role=button],input[type=submit]'));
                e=all.find(function(x){return ((x.innerText||x.value||'').trim())===target;})
                  || all.find(function(x){return ((x.innerText||'').trim()).indexOf(target)>=0;});
              }
              if(!e) return 'NOT_FOUND';
              e.scrollIntoView({block:'center'});
              e.click();
              return 'CLICKED:'+((e.innerText||e.value||'').replace(/\s+/g,' ').trim().slice(0,40));
            })(__TARGET__)
        """.trimIndent()

        /** 输入脚本：原生 setter 保证 React/Vue 受控组件也能感知变化。 */
        private val TYPE_JS = """
            (function(sel,text){
              var e=null;
              try { e=document.querySelector(sel); } catch(err) { e=null; }
              if(!e) return 'NOT_FOUND';
              e.focus();
              var proto=e.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
              var setter=Object.getOwnPropertyDescriptor(proto,'value');
              if(setter&&setter.set){ setter.set.call(e,text); } else { e.value=text; }
              e.dispatchEvent(new Event('input',{bubbles:true}));
              e.dispatchEvent(new Event('change',{bubbles:true}));
              return 'TYPED';
            })(__SEL__, __TEXT__)
        """.trimIndent()
    }
}
