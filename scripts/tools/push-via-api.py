#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 GitHub Git Data API 推送提交。

## 什么时候需要它

本机到 github.com:443 的 git 协议通道（`git-receive-pack`）连不上——实测
`git push` 会在 133 秒后超时，但同一时刻 `curl https://github.com` 与
REST API 都正常。也就是说被掐的是 git 协议，不是网络。

`clone` / `fetch` 走 `gh-proxy.com` 镜像可用（只读），`push` 没有可用镜像。
所以写操作改走 Git Data API：重建 blob / tree / commit 对象，再推进 ref。

## 用法

    export GITHUB_TOKEN=ghp_xxx          # 或 --token-file /tmp/.ghpat
    python3 scripts/tools/push-via-api.py --dry     # 只校验，不写入
    python3 scripts/tools/push-via-api.py           # 真正推送

## 安全设计

- 每个 commit 先把 tree 在远端重建，与本地 `git rev-parse <sha>^{tree}`
  严格比对；不一致立即中止，**绝不**在内容对不上时推进 ref。
- rename 且 blob 未变的文件直接引用远端已有 sha，不重复上传对象。
- 网络抖动（RemoteDisconnected）自动重试，连接强制不复用。
- ref 更新用 force=False，非快进会被 GitHub 拒绝，不会覆盖他人提交。

## 已知局限

API 生成的 commit sha 与本地不同：GitHub 会对提交信息做首尾空白规范化，
同样的 tree + message 也可能得到不同 sha。**内容（tree sha）完全一致**，
只是 sha 不同。推完若要本地与远端对齐：

    git fetch https://gh-proxy.com/https://github.com/<owner>/<repo>.git <branch>
    git reset --hard FETCH_HEAD
"""
import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import time

import requests

API_ROOT = 'https://api.github.com'


def git(*args, binary=False):
    r = subprocess.run(['git', *args], capture_output=True)
    if r.returncode:
        sys.exit(f'git {" ".join(args)} 失败：{r.stderr.decode("utf-8", "replace")[:300]}')
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def blob_sha(data: bytes) -> str:
    """本地计算 git blob sha，用于校验 API 返回。"""
    h = hashlib.sha1()
    h.update(b'blob %d\0' % len(data))
    h.update(data)
    return h.hexdigest()


class Pusher:
    def __init__(self, repo, token, branch):
        self.api = f'{API_ROOT}/repos/{repo}'
        self.branch = branch
        self.h = {
            'Authorization': f'token {token}',
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            'Connection': 'close',
        }

    def call(self, method, path, **kw):
        last = None
        for attempt in range(6):
            try:
                r = requests.request(method, f'{self.api}{path}',
                                     headers=self.h, timeout=90, **kw)
                if r.status_code in (200, 201):
                    return r.json()
                if r.status_code in (429, 500, 502, 503, 504):
                    last = f'HTTP {r.status_code}'
                    time.sleep(2 + 3 * attempt)
                    continue
                raise RuntimeError(f'{method} {path} -> {r.status_code}: {r.text[:300]}')
            except (requests.exceptions.ConnectionError, requests.exceptions.Timeout,
                    requests.exceptions.ChunkedEncodingError) as e:
                last = type(e).__name__
                time.sleep(2 + 3 * attempt)
        raise RuntimeError(f'{method} {path} 重试耗尽：{last}')

    def head(self):
        ref = self.call('GET', f'/git/refs/heads/{self.branch}')
        sha = ref['object']['sha']
        tree = self.call('GET', f'/git/commits/{sha}')['tree']['sha']
        return sha, tree

    def raw_diff(self, parent, commit):
        """[(status, oldpath, newpath, mode, oldsha, newsha)]"""
        raw = git('diff-tree', '-r', '-M', '-z', '--raw', '--no-commit-id',
                  parent, commit, binary=True)
        parts = raw.split(b'\0')
        out, i = [], 0
        while i < len(parts):
            p = parts[i]
            if not p or not p.startswith(b':'):
                i += 1
                continue
            _om, nm, os_, ns, status = p[1:].decode().split(' ')
            i += 1
            path = parts[i].decode('utf-8')
            i += 1
            newpath = None
            if status[0] in 'RC':
                newpath = parts[i].decode('utf-8')
                i += 1
            out.append((status[0], path, newpath, nm, os_, ns))
        return out

    def pending(self, head_tree, branch):
        """找出本地领先、而远端尚未包含的提交（按树比对起点，不依赖 sha 相同）。"""
        if git('rev-parse', branch + '^{tree}').strip() == head_tree:
            return []                     # 内容已一致，无论 sha 是否相同都无需推送
        commits = git('rev-list', '--reverse', branch).strip().split('\n')
        start = None
        for i, c in enumerate(commits):
            if subprocess.run(['git', 'rev-parse', '--verify', '-q', c + '^'],
                              capture_output=True).returncode:
                continue                  # 根提交无父，跳过
            parent = git('rev-parse', c + '^').strip()
            if git('rev-parse', parent + '^{tree}').strip() == head_tree:
                start = i
        if start is None:
            sys.exit('❌ 本地历史与远端树对不上（远端可能被改过，先 fetch 对齐）')
        return commits[start:]

    def push_commit(self, c, remote_sha, remote_tree, dry):
        parent = git('rev-parse', c + '^').strip()
        if git('rev-parse', parent + '^{tree}').strip() != remote_tree:
            sys.exit(f'❌ {c[:10]} 的父树与远端树不一致，中止')
        local_tree = git('rev-parse', c + '^{tree}').strip()

        entries, uploaded = [], 0
        for status, path, newpath, mode, oldsha, newsha in self.raw_diff(parent, c):
            if status == 'D':
                entries.append({'path': path, 'mode': mode, 'type': 'blob', 'sha': None})
                continue
            if status in 'RC':
                entries.append({'path': path, 'mode': mode, 'type': 'blob', 'sha': None})
                path = newpath
                if newsha == oldsha:      # 纯改名：远端已有该对象
                    entries.append({'path': path, 'mode': mode,
                                    'type': 'blob', 'sha': newsha})
                    continue
            data = git('show', f'{c}:{path}', binary=True)
            got = self.call('POST', '/git/blobs', json={
                'content': base64.b64encode(data).decode(), 'encoding': 'base64'})
            if got['sha'] != blob_sha(data):
                sys.exit(f'❌ blob sha 不符：{path}')
            entries.append({'path': path, 'mode': mode, 'type': 'blob', 'sha': got['sha']})
            uploaded += 1
            time.sleep(0.05)

        tree = self.call('POST', '/git/trees',
                         json={'base_tree': remote_tree, 'tree': entries})
        if tree['sha'] != local_tree:
            sys.exit(f'❌ tree 不一致 {tree["sha"][:10]} ≠ {local_tree[:10]}，已中止')
        print(f'   {c[:10]}  tree ✔  {uploaded} 个 blob 上传')
        if dry:
            return c, tree['sha']

        # message 取 commit 对象原文：%B 会多一个尾换行，导致 sha 偏移
        msg = git('cat-file', 'commit', c, binary=True).split(b'\n\n', 1)[1].decode('utf-8')
        meta = git('log', '-1', '--format=%an%x00%ae%x00%aI', c).split('\0')
        ident = {'name': meta[0], 'email': meta[1], 'date': meta[2]}
        commit = self.call('POST', '/git/commits', json={
            'message': msg, 'tree': tree['sha'], 'parents': [remote_sha],
            'author': ident, 'committer': ident})
        self.call('PATCH', f'/git/refs/heads/{self.branch}',
                  json={'sha': commit['sha'], 'force': False})
        print(f'   {c[:10]}  commit {commit["sha"][:10]}  ref 已前进')
        return commit['sha'], tree['sha']


def main():
    ap = argparse.ArgumentParser(description='经 GitHub API 推送（绕过 git 协议阻塞）')
    ap.add_argument('--repo', default='Kdkdmwnwdkd/Polaris')
    ap.add_argument('--branch', default='main')
    ap.add_argument('--token-file', default='/tmp/.ghpat')
    ap.add_argument('--dry', action='store_true', help='只校验 tree，不写入')
    args = ap.parse_args()

    token = os.environ.get('GITHUB_TOKEN', '')
    if not token and os.path.exists(args.token_file):
        token = open(args.token_file, encoding='utf-8').read().strip()
    if not token:
        sys.exit('缺少 token：设 GITHUB_TOKEN 或用 --token-file')

    p = Pusher(args.repo, token, args.branch)
    remote_sha, remote_tree = p.head()
    print(f'远端 {args.branch}  {remote_sha[:10]}  tree {remote_tree[:10]}')

    todo = p.pending(remote_tree, args.branch)
    if not todo:
        print('已是最新，无需推送')
        return
    print(f'待推 {len(todo)} 个提交\n')

    for c in todo:
        remote_sha, remote_tree = p.push_commit(c, remote_sha, remote_tree, args.dry)

    print(f'\n{"[dry] 未写入" if args.dry else "推送完成"}：远端 {args.branch} = {remote_sha[:10]}')
    if not args.dry:
        print(f'\n如需本地与远端对齐（sha 会不同，内容一致）：\n'
              f'  git fetch https://gh-proxy.com/https://github.com/{args.repo}.git {args.branch}\n'
              f'  git reset --hard FETCH_HEAD')


if __name__ == '__main__':
    main()
