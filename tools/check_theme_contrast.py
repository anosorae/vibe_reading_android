#!/usr/bin/env python3
"""主题色板检查：对比度达标 + M3 色彩角色无遗漏。

背景（为什么需要这个脚本）：
`lightColorScheme()` / `darkColorScheme()` 只填自己关心的角色时，未填的角色会**静默**
落回 Material 基线值（淡紫调）。而底部弹窗、对话框、下拉菜单、`surfaceTint` 恰好都读
这些不常用的角色 —— 结果是「暖米色底上浮出一层淡紫弹窗」，构建和日志都看不出来。
同理，手写的语义色很容易在「亮度」上翻车（白字压浅橙只有 2.5:1）。

做法：直接从 Color.kt / Theme.kt / PaletteSchemes.kt 解析出**全部** 12 套 colorScheme 的
实际取值，然后
1. 检查 35 个核心 M3 角色是否都显式赋值；
2. 按下方 CONTRACT 逐对核对 WCAG 对比度（正文 ≥4.5:1、图形/描边 ≥3:1）；
3. 核对共用语义色在阅读器 5 档背景上仍 ≥3:1（这些色不跟随全局主题）。

PaletteSchemes.kt 里的 8 套（黛蓝/苔绿/藕荷/青简 的亮暗）不是直接写 `lightColorScheme(`，
而是调 `lightPaletteScheme(...)` / `darkPaletteScheme(...)` 构造器，所以要先把构造器体内的
`角色 = 表达式` 取出来，再用调用点的具名参数替换进去 —— 否则这 8 套一直是**检查盲区**
（实测：黛蓝的 `secondary` 曾长期只有 4.17:1，超过 CONTRACT 阈值却没人发现）。

用法：`python tools/check_theme_contrast.py`（退出码非 0 = 有失败项）
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COLOR_KT = ROOT / "app/src/main/java/com/vibereading/app/ui/theme/Color.kt"
THEME_KT = ROOT / "app/src/main/java/com/vibereading/app/ui/theme/Theme.kt"
PALETTE_KT = ROOT / "app/src/main/java/com/vibereading/app/ui/theme/PaletteSchemes.kt"

# 核心 M3 色彩角色：漏掉任何一个都会落回 Material 基线值
REQUIRED_ROLES = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground",
    "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint",
    "surfaceBright", "surfaceDim",
    "surfaceContainer", "surfaceContainerLow", "surfaceContainerLowest",
    "surfaceContainerHigh", "surfaceContainerHighest",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim",
    "inverseSurface", "inverseOnSurface",
]

# (前景角色, 背景角色, 最低对比度)
CONTRACT = [
    ("onPrimary", "primary", 4.5),
    ("onPrimaryContainer", "primaryContainer", 4.5),
    ("onSecondary", "secondary", 4.5),
    ("onSecondaryContainer", "secondaryContainer", 4.5),
    ("onTertiary", "tertiary", 4.5),
    ("onTertiaryContainer", "tertiaryContainer", 4.5),
    ("onError", "error", 4.5),
    ("onErrorContainer", "errorContainer", 4.5),
    ("onBackground", "background", 4.5),
    ("onSurface", "surface", 4.5),
    ("onSurfaceVariant", "surfaceVariant", 4.5),
    # 强调色会被当文字用（排序栏、分区标题、选中态标签），所以也要过正文阈值
    ("primary", "background", 4.5),
    ("secondary", "background", 4.5),
    ("error", "background", 4.5),
    # 弹窗/抽屉/菜单里的文字：容器色是 surfaceContainer* 而不是 surface
    ("onSurface", "surfaceContainerHigh", 4.5),
    ("onSurfaceVariant", "surfaceContainerLow", 4.5),
    ("onSurfaceVariant", "surfaceContainerHigh", 4.5),
    ("onSurfaceVariant", "surfaceContainerHighest", 4.5),
    ("outline", "background", 3.0),
]

# 阅读器语义色（ChapterStatusUi / ReaderChrome 无条件使用，不跟随全局主题）。
# 亮/暗各一套：单一中间调无法同时满足「浅底当正文 ≥4.5:1」和「深底当图形 ≥3:1」，
# 两个亮度区间不相交，所以 ChapterStatusUi 必须按调用方所在表面的深浅选一套。
READER_LIGHT_TOKENS = ["Sage", "BlueMuted", "RedMuted", "Amber", "Outline"]
READER_DARK_TOKENS = ["Done", "InProgress", "Failed", "TooLong", "Pending", "TextMuted"]
READER_MIN = 3.0


def srgb_to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hex_color: str) -> float:
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    return 0.2126 * srgb_to_linear(r) + 0.7152 * srgb_to_linear(g) + 0.0722 * srgb_to_linear(b)


def contrast(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def parse_tokens() -> dict[str, str]:
    """Color.kt → {'VibeColors.Sienna': '#A65332', ...}"""
    text = COLOR_KT.read_text(encoding="utf-8")
    tokens: dict[str, str] = {}
    obj = None
    for line in text.splitlines():
        m = re.match(r"object\s+(\w+)\s*\{", line)
        if m:
            obj = m.group(1)
            continue
        if line.startswith("}"):
            obj = None
            continue
        m = re.match(r"\s*val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{6,8})\)", line)
        if m and obj:
            value = m.group(2)
            # 丢掉 alpha 通道（本脚本只处理不透明语义色）
            if len(value) == 8:
                value = value[2:]
            tokens[f"{obj}.{m.group(1)}"] = "#" + value.upper()
    return tokens


def parse_schemes(tokens: dict[str, str]) -> dict[str, dict[str, str]]:
    """Theme.kt → {'vibeColorScheme': {'primary': '#A65332', ...}, ...}"""
    text = THEME_KT.read_text(encoding="utf-8")
    schemes: dict[str, dict[str, str]] = {}
    for m in re.finditer(
        r"private fun (\w+ColorScheme)\(\)\s*=\s*(light|dark)ColorScheme\((.*?)\n\)",
        text,
        re.S,
    ):
        name, _mode, body = m.group(1), m.group(2), m.group(3)
        roles: dict[str, str] = {}
        for rm in re.finditer(r"(\w+)\s*=\s*([^,\n]+)", body):
            role, expr = rm.group(1), rm.group(2).strip()
            hexv = tokens.get(expr)
            if hexv is None:
                cm = re.match(r"Color\(0x([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\)", expr)
                if cm:
                    raw = cm.group(1)
                    hexv = "#" + (raw[2:] if len(raw) == 8 else raw).upper()
                elif expr == "Color.Black":
                    hexv = "#000000"
                elif expr == "Color.White":
                    hexv = "#FFFFFF"
            if hexv:
                roles[role] = hexv
        schemes[name] = roles
    return schemes


def resolve_expr(expr: str, tokens: dict[str, str], env: dict[str, str], depth: int = 0) -> str | None:
    """把一个角色表达式求值成 #RRGGBB：具名参数 → 调色板 token → Color 字面量。

    参数名要**递归**求值：构造器体里的 `surface = background` 拿到的是调用点传进来的
    `IndigoColors.Cream`，还要再解析一层才是 hex。
    """
    expr = expr.strip()
    if depth > 8:
        return None
    if expr in env:
        return resolve_expr(env[expr], tokens, env, depth + 1)
    if expr in tokens:
        return tokens[expr]
    if expr == "Color.White":
        return "#FFFFFF"
    if expr == "Color.Black":
        return "#000000"
    m = re.match(r"Color\(0x([0-9A-Fa-f]{6,8})\)", expr)
    if m:
        raw = m.group(1)
        return "#" + (raw[2:] if len(raw) == 8 else raw).upper()
    return None


def parse_palette_schemes(tokens: dict[str, str]) -> dict[str, dict[str, str]]:
    """PaletteSchemes.kt → 由 light/darkPaletteScheme 构造的 8 套 colorScheme。

    构造器体内的 `角色 = 表达式` 用调用点的具名参数代入；`fun x() = yDarkColorScheme()`
    这种纯别名按被别名的那套复制。角色名与构造器形参名恰好同名，所以代入是直接的。
    """
    text = PALETTE_KT.read_text(encoding="utf-8")
    builders: dict[str, dict[str, str]] = {}
    for m in re.finditer(
        r"private fun (\w+)\((.*?)\)\s*:\s*ColorScheme\s*=\s*(?:light|dark)ColorScheme\((.*?)\n\)",
        text,
        re.S,
    ):
        builders[m.group(1)] = {
            rm.group(1): rm.group(2).strip()
            for rm in re.finditer(r"(\w+)\s*=\s*([^,\n]+)", m.group(3))
        }

    schemes: dict[str, dict[str, str]] = {}
    for m in re.finditer(r"fun (\w+)\(\)\s*=\s*(\w+)\(", text):
        name, callee = m.group(1), m.group(2)
        if callee not in builders:
            continue
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += (text[i] == "(") - (text[i] == ")")
            i += 1
        env = {
            rm.group(1): rm.group(2).strip()
            for rm in re.finditer(r"(\w+)\s*=\s*([^,]+)", text[m.end():i - 1])
        }
        roles = {}
        for role, expr in builders[callee].items():
            value = resolve_expr(expr, tokens, env)
            if value:
                roles[role] = value
        schemes[name] = roles

    # `fun mossDarkColorScheme() = indigoDarkColorScheme()` 这类纯别名：整份复制
    for m in re.finditer(r"fun (\w+)\(\)\s*=\s*(\w+)\(\)", text):
        name, target = m.group(1), m.group(2)
        seen: set[str] = set()
        while target not in schemes and target not in seen:
            seen.add(target)
            nxt = re.search(rf"fun {target}\(\)\s*=\s*(\w+)\(\)", text)
            if not nxt:
                break
            target = nxt.group(1)
        if target in schemes:
            schemes[name] = dict(schemes[target])
    return schemes


def parse_reader_presets(tokens: dict[str, str]) -> dict[str, str]:
    text = COLOR_KT.read_text(encoding="utf-8")
    block = re.search(r"object ReaderBgPresets\s*\{(.*?)\n\}", text, re.S)
    if not block:
        return {}
    return {
        m.group(1): "#" + m.group(2).upper()
        for m in re.finditer(r"val\s+(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", block.group(1))
    }


def main() -> int:
    tokens = parse_tokens()
    schemes = parse_schemes(tokens)
    palette_schemes = parse_palette_schemes(tokens)
    presets = parse_reader_presets(tokens)
    failures: list[str] = []

    if not schemes or not palette_schemes:
        print("解析 colorScheme 失败：Theme.kt 应仍是 `private fun xxxColorScheme() = lightColorScheme(...)`，")
        print("PaletteSchemes.kt 应仍是 `private fun xxxPaletteScheme(参数) : ColorScheme = lightColorScheme(...)` + 具名参数调用。")
        return 1
    schemes.update(palette_schemes)

    print("=" * 74)
    print("1) M3 色彩角色完整性")
    print("=" * 74)
    for name, roles in schemes.items():
        missing = [r for r in REQUIRED_ROLES if r not in roles]
        status = "OK  " if not missing else "FAIL"
        print(f"  {status} {name:24s} {len(roles)} 个角色" + (f"  缺: {', '.join(missing)}" if missing else ""))
        if missing:
            failures.append(f"{name} 缺少角色: {', '.join(missing)}")

    print()
    print("=" * 74)
    print("2) WCAG 对比度（正文类 ≥4.5:1，描边/图形 ≥3:1）")
    print("=" * 74)
    for name, roles in schemes.items():
        print(f"  -- {name} --")
        for fg, bg, minimum in CONTRACT:
            if fg not in roles or bg not in roles:
                continue
            ratio = contrast(roles[fg], roles[bg])
            ok = ratio >= minimum
            if not ok:
                failures.append(f"{name}: {fg} on {bg} = {ratio:.2f} (需 ≥{minimum})")
            print(f"    {'OK  ' if ok else 'FAIL'} {fg:22s}/{bg:22s} {ratio:5.2f} (≥{minimum})")

    if presets:
        print()
        print("=" * 74)
        print("3) 章节状态色 × 阅读背景（不跟随全局主题，需两边都能看）")
        print("=" * 74)
        light_bgs = {k: v for k, v in presets.items() if k != "DarkNight"}
        header = "".join(f"{p:>12s}" for p in light_bgs)
        print(f"    {'亮色档（4 档浅底）':22s}{header}")
        for token in READER_LIGHT_TOKENS:
            hexv = tokens.get(f"VibeColors.{token}")
            if not hexv:
                continue
            row = ""
            for pname, phex in light_bgs.items():
                ratio = contrast(hexv, phex)
                if ratio < READER_MIN:
                    failures.append(
                        f"VibeColors.{token} on ReaderBgPresets.{pname} = {ratio:.2f} (需 ≥{READER_MIN})"
                    )
                    row += f"{ratio:>11.2f}!"
                else:
                    row += f"{ratio:>12.2f}"
            print(f"    {token:22s}{row}")

        # 深色档要对 DarkNight，也要对全局主题的两套深色表面（面板/抽屉用的是它们）
        dark_bgs = {
            "DarkNight": presets["DarkNight"],
            "VibeDarkSurface": tokens.get("VibeDarkColors.Surface", "#221F1B"),
            "WereadDarkSurface": tokens.get("WereadDarkColors.Surface", "#1A211E"),
        }
        header = "".join(f"{p:>18s}" for p in dark_bgs)
        print(f"    {'深色档':22s}{header}")
        for token in READER_DARK_TOKENS:
            hexv = tokens.get(f"ChapterStatusDarkColors.{token}")
            if not hexv:
                continue
            row = ""
            for pname, phex in dark_bgs.items():
                ratio = contrast(hexv, phex)
                if ratio < READER_MIN:
                    failures.append(
                        f"ChapterStatusDarkColors.{token} on {pname} = {ratio:.2f} (需 ≥{READER_MIN})"
                    )
                    row += f"{ratio:>17.2f}!"
                else:
                    row += f"{ratio:>18.2f}"
            print(f"    {token:22s}{row}")

    print()
    print("=" * 74)
    if failures:
        print(f"结果：{len(failures)} 项不达标")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("结果：全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
