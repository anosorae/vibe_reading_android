#!/usr/bin/env python3
"""
检查「catch / runCatching 后必须落日志」约定的静态脚本（AGENTS.md 编码约定）。

规则：错误路径（catch / Result.exceptionOrNull()）除了写 UI 状态外应调用
`AppLog.put`（或 LogUtils / logger / android.util.Log）落日志，便于用户在
「设置 → 调试 → 日志」中定位 bug；不要散落 printStackTrace 或静默吞掉异常。

本脚本是启发式（文本 + 花括号配对），定位以下疑似点供人工确认：
  1. `catch (e: X) { ... }`：catch 块体内既没有 AppLog/Log/logger/LogUtils、
     也没有 rethrow（throw）时，标记为「疑似吞异常」。
  2. `runCatching { ... }`：整条链上没有 .onFailure{...} 且内层没有落日志时，
     标记为「疑似吞异常」（注意：返回可空值/默认值的 runCatching 是合法用法，
     需人工甄别）。

不是错误、只是需要人看一眼的清单；用法：
    python tools/check_log_convention.py            # 扫 app/src/main/java
    python tools/check_log_convention.py --dir 路径   # 扫指定目录
退出码：有疑似点返回 1，干净返回 0。
"""
from __future__ import annotations

import argparse
import os
import re
import sys

# 出现在 catch 块/onFailure 里即视为「已落日志/有处理」的关键词
LOG_MARKERS = (
    "AppLog", "LogUtils", "logger", ".put(", "printStackTrace",
    "android.util.Log",
)
RETHROW_MARKERS = ("throw",)
# 同一行内出现即忽略的常见非日志误报（合法吞并从上下文恢复）
IGNORE_INLINE = ("//",)

def strip_strings_code(code: str) -> str:
    """把字符串/注释内容替换为空格，避免括号匹配被字符串里的花括号干扰。"""
    out = []
    i = 0
    n = len(code)
    while i < n:
        c = code[i]
        if c == '"':
            j = i + 1
            while j < n:
                if code[j] == '\\':
                    j += 2
                    continue
                if code[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        elif c == "'" :
            out.append("'")
            i += 1
        elif c == '/' and i + 1 < n and code[i + 1] == '/':
            j = code.find('\n', i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == '/' and i + 1 < n and code[i + 1] == '*':
            j = code.find('*/', i + 2)
            j = n - 2 if j < 0 else j
            out.append(" " * (j - i + 2))
            i = j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)

def find_catch_blocks(code: str, stripp: bool = True):
    """返回 catch 块的 (body_start, body_end) 区间列表（基于剥离字符串后的文本）。"""
    src = strip_strings_code(code) if stripp else code
    blocks = []
    i = 0
    n = len(src)
    while i < n:
        m = re.match(r'catch\b', src[i:])
        if m:
            # 跳过 catch (…) 参数
            j = i + m.end()
            while j < n and src[j] != '{':
                j += 1
            if j >= n:
                i += m.end()
                continue
            # 匹配 body
            start = j
            depth = 0
            k = j
            while k < n:
                if src[k] == '{':
                    depth += 1
                elif src[k] == '}':
                    depth -= 1
                    if depth == 0:
                        blocks.append((start, k))
                        break
                k += 1
            i = k + 1
        else:
            i += m.end() if m else 1
    return blocks

def find_run_catching(code: str, stripp: bool = True):
    """返回 runCatching { ... } 的 body 区间（调用点行号用于报告）。"""
    src = strip_strings_code(code) if stripp else code
    blocks = []
    i = 0
    n = len(src)
    while i < n:
        m = re.search(r'runCatching\b', src[i:])
        if not m:
            break
        j = i + m.end()
        while j < n and src[j] not in '({':
            if src[j] == '\n':
                # 运行行 continue 是正常写法，允许换行后找 {
                pass
            j += 1
        if j >= n or src[j] == '(':
            # 有显式括号包装，跳到其结束
            if src[j] == '(':
                depth = 0
                j0 = j
                while j < n:
                    if src[j] == '(':
                        depth += 1
                    elif src[j] == ')':
                        depth -= 1
                        if depth == 0:
                            j += 1
                            break
                    j += 1
                while j < n and src[j] != '{':
                    j += 1
            if j >= n:
                break
        start = j
        depth = 0
        k = j
        while k < n:
            if src[k] == '{':
                depth += 1
            elif src[k] == '}':
                depth -= 1
                if depth == 0:
                    blocks.append((start, k, i))
                    break
            k += 1
        if k >= n:
            break
        i = k + 1
    return blocks

def line_of(code: str, idx: int) -> int:
    return code.count('\n', 0, idx) + 1

def has_log(code: str, start: int, end: int) -> bool:
    seg = code[start:end]
    return any(m in seg for m in LOG_MARKERS)

def has_rethrow(code: str, start: int, end: int) -> bool:
    seg = code[start:end]
    return any(m in seg for m in RETHROW_MARKERS)

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join("app", "src", "main", "java"))
    args = ap.parse_args()

    root = os.path.abspath(args.dir)
    findings = []

    for dirpath, _, files in os.walk(root):
        for fname in files:
            if not fname.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fname)
            code = open(path, encoding="utf-8").read()
            _src = strip_strings_code(code)

            for s, e in find_catch_blocks(code):
                body = code[s + 1:e]
                # 忽略单行内联注释导致的空判（例如纯 // 忽略的行不算空块）
                if not body.strip() or all(
                    any(ig in ln for ig in IGNORE_INLINE) and not ln.strip()
                    for ln in body.splitlines()
                ):
                    continue
                if not has_log(code, s, e) and not has_rethrow(code, s, e):
                    ln = line_of(code, s)
                    snippet = _src[s:e].splitlines()[:2]
                    findings.append((rel(path, root), ln, "catch 疑似吞异常（无日志/无 rethrow）", snippet))

            for s, e, callidx in find_run_catching(code):
                ln = line_of(code, callidx)
                inner = code[s + 1:e]
                if has_log(code, s, e):
                    continue
                # 链式 .onFailure{...} 也算已处理
                rest = code[e + 1: e + 120]
                if ".onFailure" in rest or ".getOrNull" in rest:
                    continue
                if not has_rethrow(code, s, e):
                    snippet = code[s:e].splitlines()[:2]
                    findings.append((rel(path, root), ln, "runCatching 疑似吞异常（无日志/无 onFailure 处理）", snippet))

    if findings:
        print("疑似「吞异常且未落日志」的点（需人工确认，见 AGENTS.md 约定）：")
        cur = None
        for path, ln, kind, snippet in findings:
            print(f"  {path}:{ln}  [{kind}]")
            for s_ in snippet:
                s_ = s_.strip()
                if s_:
                    print(f"      {s_}")
        print(f"\n共 {len(findings)} 处。合法用法（返回默认值/可空）可加 --ignore 或直接不改。")
        return 1
    print("干净：未发现无日志的 catch / runCatching。")
    return 0

def rel(path: str, root: str) -> str:
    return os.path.relpath(path, os.path.dirname(os.path.dirname(root)))

if __name__ == "__main__":
    sys.exit(main())
