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

    # GitHub 会把提交信息末尾的换行补回来。发送前先去掉，它就只补一个，
    # 结果与本地 git commit 生成的对象一致，SHA 不会分叉。
    msg = sh("git", "log", "-1", "--pretty=%B").decode().rstrip("\n")
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
        align_local(c, msg, tree["sha"])

    ref = call("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
               {"sha": c["sha"], "force": False})
    print("已更新 %s -> %s" % (ref["ref"], ref["object"]["sha"]))


def align_local(c, msg, tree):
    """极端情况下 SHA 仍不同时，把本地分支对齐到远程，避免下次推送 non-fast-forward。

    按「消息末尾换行数 1 或 2」两种可能重算对象哈希，命中就写进本地库并重置分支。
    """
    tz = (sh("git", "log", "-1", "--pretty=%aI").decode().strip()[-6:] or "+0000")
    epoch = sh("git", "log", "-1", "--pretty=%at").decode().strip()
    head_raw = (
        "tree %s\nparent %s\nauthor %s <%s> %s %s\ncommitter %s <%s> %s %s\n\n"
        % (tree, c["parents"][0]["sha"],
           c["author"]["name"], c["author"]["email"], epoch, tz,
           c["committer"]["name"], c["committer"]["email"], epoch, tz)
    ).encode()
    body = msg.encode().rstrip(b"\n")
    for tail in (1, 2):
        raw = head_raw + body + b"\n" * tail
        r = subprocess.run(["git", "hash-object", "-t", "commit", "-w", "--stdin"],
                           input=raw, capture_output=True)
        if r.stdout.decode().strip() == c["sha"]:
            subprocess.run(["git", "reset", "--hard", c["sha"]], check=True,
                           stdout=subprocess.DEVNULL)
            print("本地已对齐到 %s" % c["sha"])
            return
    print("本地重建失败，下次联网时 git fetch 后 reset 到 origin/%s 即可" % BRANCH)


if __name__ == "__main__":
    main()
