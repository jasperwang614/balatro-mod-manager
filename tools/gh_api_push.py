# -*- coding: utf-8 -*-
"""在 github.com:443 被墙、只有 api.github.com 可达的情况下，用 Git Data API 推送。

流程与 git push 等价：上传改动的 blob -> 以父树为 base 建新树 -> 用原始 author/committer
与提交信息建 commit -> 更新 refs/heads/main。因为树的哈希、提交信息、时间戳都原样复刻，
最终 commit SHA 应当与本地 HEAD 完全一致。
"""
import base64
import json
import os
import subprocess
import sys

REPO = "jasperwang614/balatro-mod-manager"
BRANCH = "main"
API = "https://api.github.com"


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, check=True, **kw).stdout


def token():
    p = subprocess.run(
        ["git", "credential", "fill"],
        input=b"protocol=https\nhost=github.com\n\n",
        capture_output=True,
        check=True,
    ).stdout.decode()
    for line in p.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise SystemExit("没有取到 GitHub 凭据")


TOK = token()


def call(method, path, body=None):
    import urllib.request
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "token " + TOK)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "balatro-mod-manager")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise SystemExit("%s %s -> %s\n%s" % (method, path, e.code, detail[:800]))


def blob_sha(path):
    raw = sh("git", "show", "HEAD:" + path)
    r = call("POST", "/repos/%s/git/blobs" % REPO,
             {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
    return r["sha"]


def main():
    parent = sh("git", "rev-parse", "HEAD^").decode().strip()
    head = sh("git", "rev-parse", "HEAD").decode().strip()
    # base_tree 必须是服务端已存在的树，所以取父提交的树（新树只在本地有）
    base_tree = sh("git", "rev-parse", "HEAD^^{tree}").decode().strip()
    want_tree = sh("git", "rev-parse", "HEAD^{tree}").decode().strip()

    changed = sh("git", "diff", "--name-only", parent, head).decode().split()
    print("改动文件 %d 个" % len(changed))

    entries = []
    for path in changed:
        sha = blob_sha(path)
        mode = sh("git", "ls-tree", "HEAD", path).decode().split()[0]
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
        print("  blob %s %s" % (sha[:8], path))

    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": entries})
    print("新树 %s (期望 %s)" % (tree["sha"], want_tree))
    if tree["sha"] != want_tree:
        raise SystemExit("树哈希对不上，中止（可能有文件模式或内容差异）")

    msg = sh("git", "log", "-1", "--pretty=%B").decode()
    # git 的 %B 会带结尾换行，还原成 cat-file 里的原始 message
    if not msg.endswith("\n"):
        msg += "\n"
    author = sh("git", "log", "-1", "--pretty=%an\t%ae\t%aI").decode().rstrip("\n").split("\t")
    committer = sh("git", "log", "-1", "--pretty=%cn\t%ce\t%cI").decode().rstrip("\n").split("\t")

    c = call("POST", "/repos/%s/git/commits" % REPO, {
        "message": msg,
        "tree": tree["sha"],
        "parents": [parent],
        "author": {"name": author[0], "email": author[1], "date": author[2]},
        "committer": {"name": committer[0], "email": committer[1], "date": committer[2]},
    })
    print("新提交 %s (本地 %s)" % (c["sha"], head))
    if c["sha"] != head:
        print("注意：SHA 与本地不一致，但内容等价。之后本地 fetch 会分叉，可 reset 到远程。")

    ref = call("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
               {"sha": c["sha"], "force": False})
    print("已更新 %s -> %s" % (ref["ref"], ref["object"]["sha"]))


if __name__ == "__main__":
    main()
