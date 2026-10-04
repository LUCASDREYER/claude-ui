#!/usr/bin/env python3
"""Claude Code status line: model | directory | git branch | context usage.

Reads the session JSON Claude Code sends on stdin and prints one ANSI-colored line.
Standard library only, and no subprocesses (the branch comes from .git/HEAD), so it stays fast.
Set NO_COLOR to disable colors.
"""
import json
import os
import sys

COLOR = "NO_COLOR" not in os.environ
DIM, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN = "2", "31", "32", "33", "34", "35", "36"


def paint(code, text):
    return f"\033[{code}m{text}\033[0m" if COLOR else text


def short_dir(path):
    home = os.path.expanduser("~")
    if path == home or path.startswith(home + os.sep):
        path = "~" + path[len(home):]
    parts = path.split(os.sep)
    if len(path) > 40 and len(parts) > 3:
        path = os.sep.join(["…"] + parts[-2:])
    return path


def git_branch(start):
    """Walks up to the nearest .git and reads HEAD; follows `gitdir:` files for worktrees."""
    d = os.path.abspath(start)
    while True:
        git = os.path.join(d, ".git")
        if os.path.isfile(git):
            with open(git) as f:
                line = f.readline().strip()
            if line.startswith("gitdir:"):
                git = os.path.join(d, line[len("gitdir:"):].strip())
        if os.path.isdir(git):
            try:
                with open(os.path.join(git, "HEAD")) as f:
                    head = f.read().strip()
            except OSError:
                return None
            prefix = "ref: refs/heads/"
            return head[len(prefix):] if head.startswith(prefix) else head[:7]
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def tokens(n):
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}".rstrip("0").rstrip(".") + "M"
    return f"{n // 1000}k" if n >= 1000 else str(n)


def context(data):
    cw = data.get("context_window") or {}
    size = cw.get("context_window_size") or 0
    usage = cw.get("current_usage") or {}
    # Input-only, matching Claude Code's own used_percentage.
    used = cw.get("total_input_tokens") or sum(
        usage.get(k) or 0 for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
    )
    pct = cw.get("used_percentage")
    if pct is None and size and used:
        pct = used * 100 / size
    if pct is None:
        return None
    text = f"ctx {pct:.0f}%"
    if used and size:
        text += f" {tokens(used)}/{tokens(size)}"
    return paint(RED if pct >= 80 else YELLOW if pct >= 50 else GREEN, text)


def main():
    try:
        data = json.load(sys.stdin)
    except ValueError:
        print("statusline: no input")
        return
    model = (data.get("model") or {}).get("display_name") or "?"
    cwd = (data.get("workspace") or {}).get("current_dir") or data.get("cwd") or os.getcwd()

    parts = [paint(CYAN, model), paint(BLUE, short_dir(cwd))]
    branch = git_branch(cwd)
    if branch:
        parts.append(paint(MAGENTA, f"git:{branch}"))
    ctx = context(data)
    if ctx:
        parts.append(ctx)
    print(paint(DIM, " │ ").join(parts))


if __name__ == "__main__":
    main()
