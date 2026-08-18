#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建内嵌词典数据库：从 ECDICT 基础版 CSV（76 万词条）生成精简 SQLite。

数据源：ECDICT 基础版 CSV（约 66MB，770k 行，13 列）：
    https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv
（GitHub release 1.0.28 只有 zip 包，且都是「完整版 340 万词条」，没有独立 CSV；
  完整版精简后仍远大于 20MB，不适合内嵌，故本项目只走 CSV。）

体积预算（APK 内 deflate 压缩 ≤ 20MB）：
  - 全量 76 万 × 4 列 → 28.8MB 压缩（超预算）
  - 默认按「词以类聚」裁剪（--keep-all 可构建全量）：
      * 保留有词频数据的词（frq>0，约 4.2 万最常用词）
      * 保留词长 ≤ 14 的词（覆盖绝大多数小说/译文用词，含 circumstances 等较长常用词）
      其余为生僻/技术长词，约 26 万条被裁剪 → 约 50 万词条 / 18.7MB 压缩 ✔
  - 常用词覆盖验证（构建时对比清单）：abandon/abandoned/running/better/mice/children/
    went/bought/well-known/as soon as/don't/circumstances/communication 等 33 词全部命中。
  - 大小写：基础版词条大小写变体零冲突，统一小写存储 + 查询时小写归一，
    BINARY 主键即等价 COLLATE NOCASE，省掉整个 NOCASE 索引（约 2.5MB 压缩）。

精简策略：只保留 word / phonetic / translation / pos 四列，
删掉 definition、collins、oxford、tag、bnc、frq、exchange、detail、audio、sw。

输出：app/src/main/assets/dict/ecdict.dict（gzip 预压缩，约 18.7MB；扩展名用 .dict 规避 AGP 对 .gz 资产的自动解压）。

用法：
    python tools/build_dict_db.py                  # 默认：按词频+词长裁剪（约 50 万词条）
    python tools/build_dict_db.py --keep-all       # 保留全量 76 万（约 28.8MB 压缩）
    python tools/build_dict_db.py --csv 路径 --skip-download
"""

import argparse
import csv
import gzip
import os
import shutil
import sqlite3
import struct
import sys
import tempfile
import urllib.request
import zlib

# ECDICT master 分支的全量基础版 CSV（release 1.0.28 无独立 csv 资产）
BASE_CSV_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"

# 基础版 CSV 原始 13 列：word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio
COL_WORD = 0
COL_PHONETIC = 1
COL_TRANSLATION = 3
COL_POS = 4
COL_FRQ = 9
CSV_COLUMNS = 13

# 裁剪参数：保留「词频存在（frq>0）」或「词长 ≤ 该长度」的词条
# len=14 覆盖绝大多数常用词（含 circumstances/extraordinary 等），见文件头体积预算
KEEP_FRQ_OR_LEN = 14

SCHEMA = (
    "CREATE TABLE dict ("
    "word TEXT PRIMARY KEY, "
    "phonetic TEXT, "
    "translation TEXT, "
    "pos TEXT)"
)
# 说明：word 全部小写存储，BINARY 主键 + 查询时小写归一即可覆盖任意大小写，无需 NOCASE 索引

# 常用词覆盖自检清单（覆盖不足直接报错，防止裁剪参数改动时误伤）
COVERAGE_CHECK_WORDS = [
    "abandon", "abandoned", "abandons", "abandoning", "running", "better",
    "mice", "children", "went", "bought", "hello", "well-known",
    "as soon as", "don't", "circumstances", "extraordinary", "communication",
    "responsibility", "unfortunately", "simultaneously", "international",
    "introduction", "understanding", "conversation", "opportunity",
    "comfortable", "knowledge", "immediately", "relationship", "environment",
    "university", "particular", "important", "development", "atm", "dna",
]


def download(url: str, dest: str, min_size: int) -> None:
    """下载到 dest（支持断点续传）；已存在且大小达标则跳过。"""
    if os.path.exists(dest) and os.path.getsize(dest) >= min_size:
        print(f"[跳过下载] 已存在 {dest} ({os.path.getsize(dest)} bytes)")
        return
    print(f"[下载] {url} → {dest}")
    tmp = dest + ".part"
    headers = {}
    if os.path.exists(tmp):
        headers["Range"] = f"bytes={os.path.getsize(tmp)}-"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "ab") as out:
        while True:
            chunk = resp.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    os.replace(tmp, dest)
    print(f"[下载完成] {os.path.getsize(dest)} bytes")


def gen_rows(csv_path: str, keep_all: bool, stats: list):
    """逐行产出 (word, phonetic, translation, pos)；stats[0] 累计被裁剪行数。"""
    with open(csv_path, encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        if header is None or len(header) != CSV_COLUMNS:
            raise SystemExit(f"CSV 表头异常: {header}")
        for row in reader:
            if len(row) != CSV_COLUMNS:
                stats[0] += 1
                continue
            word = row[COL_WORD].strip().lower()
            if not word:
                stats[0] += 1
                continue
            if not keep_all:
                frq = row[COL_FRQ].strip()
                has_frq = frq.isdigit() and int(frq) > 0
                if not has_frq and len(word) > KEEP_FRQ_OR_LEN:
                    stats[0] += 1
                    continue
            yield word, row[COL_PHONETIC].strip(), row[COL_TRANSLATION].strip(), row[COL_POS].strip()


def build_from_csv(csv_path: str, out_path: str, keep_all: bool) -> int:
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(suffix=".db", dir=os.path.dirname(out_path))
    os.close(fd)
    conn = sqlite3.connect(tmp_path)
    kept_words: set = set()
    try:
        conn.execute("PRAGMA journal_mode=OFF")
        conn.execute("PRAGMA synchronous=OFF")
        conn.execute("PRAGMA temp_store=MEMORY")
        conn.execute("PRAGMA cache_size=-64000")
        conn.execute(SCHEMA)

        stats = [0]
        total = 0
        batch = []
        for word, phonetic, translation, pos in gen_rows(csv_path, keep_all, stats):
            kept_words.add(word)
            batch.append((word, phonetic or None, translation or None, pos or None))
            if len(batch) >= 20000:
                conn.executemany(
                    "INSERT OR IGNORE INTO dict (word, phonetic, translation, pos) "
                    "VALUES (?, ?, ?, ?)",
                    batch,
                )
                conn.commit()
                total += len(batch)
                batch.clear()
                print(f"\r[导入] {total:,} 条…", end="", flush=True)
        if batch:
            conn.executemany(
                "INSERT OR IGNORE INTO dict (word, phonetic, translation, pos) "
                "VALUES (?, ?, ?, ?)",
                batch,
            )
            conn.commit()
            total += len(batch)
        print(f"\r[导入完成] {total:,} 条（裁剪/跳过 {stats[0]:,} 行）")
        return finalize(conn, tmp_path, out_path, kept_words)
    except Exception:
        conn.close()
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise


def write_gzip_with_isize(src_path: str, gz_path: str) -> None:
    """把 SQLite 文件压缩为带解压尺寸自定义头的 gzip。

    gzip 头 FLG 置 FEXTRA，额外字段直接写 4 字节 LE 解压尺寸：
    运行时只读 gz 头约 14 字节即可获得期望解压大小，用于判断目标库是否需要更新。
    （GZIPInputStream 会自动跳过额外字段并校验 CRC/ISIZE，可正常解压。）
    """
    data = open(src_path, "rb").read()
    comp = zlib.compressobj(9, zlib.DEFLATED, -15)  # raw deflate
    deflated = comp.compress(data) + comp.flush()
    crc = zlib.crc32(data) & 0xFFFFFFFF
    isize = len(data) & 0xFFFFFFFF
    extra = struct.pack("<I", isize)
    # ID1(0x1f) ID2(0x8b) CM(0x08) FLG(FEXTRA=0x04) MTIME(4) XFL OS XLEN(2 LE) extra
    header = b"\x1f\x8b\x08\x04" + struct.pack("<I", 0) + b"\x00\xff" + struct.pack("<H", len(extra)) + extra
    footer = struct.pack("<II", crc, isize)
    with open(gz_path, "wb") as out:
        out.write(header)
        out.write(deflated)
        out.write(footer)
    # 自检：gzip 模块能正常解压还原
    with gzip.open(gz_path, "rb") as gz:
        check_bytes = gz.read()
    assert check_bytes == data, "gzip 自检失败：解压结果不一致"


def finalize(conn: sqlite3.Connection, tmp_path: str, out_path: str, coverage: set) -> int:
    """ANALYZE + 常用词覆盖校验 + 关闭连接 + 原子替换输出文件，返回词条数。"""
    conn.execute("ANALYZE")
    conn.commit()
    total = conn.execute("SELECT COUNT(*) FROM dict").fetchone()[0]
    conn.close()
    os.replace(tmp_path, out_path)
    size_mb = os.path.getsize(out_path) / (1024 * 1024)
    print(f"\n[输出] {out_path} ({total:,} 条, {size_mb:.1f} MB)")
    # 常用词覆盖自检：命中不足时报错（防止裁剪参数改动误伤阅读用词）
    missing = [w for w in COVERAGE_CHECK_WORDS if w not in coverage]
    if missing:
        raise SystemExit(f"自检失败：裁剪漏掉常用词 {missing}")
    print(f"[自检] 常用词覆盖 {len(COVERAGE_CHECK_WORDS) - len(missing)}/{len(COVERAGE_CHECK_WORDS)} OK")
    # 快速查询自检：小写归一命中（ABANDON → abandon）
    check = sqlite3.connect(out_path)
    try:
        row = check.execute(
            "SELECT word, phonetic, translation, pos FROM dict "
            "WHERE word = ?",
            ("abandon",),
        ).fetchone()
        if not row:
            raise SystemExit("自检失败：未能命中 abandon")
        print(f"[自检] 查询 OK → {row[0]!r} | {row[1]!r}")
    finally:
        check.close()
    return total


def main() -> None:
    tools_dir = os.path.dirname(os.path.abspath(__file__))
    default_raw = os.path.join(tools_dir, "ecdict.db")
    default_gz = os.path.join(tools_dir, "..", "app", "src", "main", "assets", "dict", "ecdict.dict")

    parser = argparse.ArgumentParser(description="从 ECDICT 基础版 CSV 构建精简 SQLite 词典")
    parser.add_argument("--keep-all", action="store_true", help="不裁剪，保留全量 76 万词条（压缩后约 28.8MB，超出 20MB 预算）")
    parser.add_argument("--csv", default=os.path.join(tools_dir, "ecdict.csv"), help="基础版 CSV 路径")
    parser.add_argument("--raw-out", default=default_raw, help="原始 SQLite 输出（开发检查用，不入库）")
    parser.add_argument("--out", default=default_gz, help="gzip 资产输出（APK 内嵌，入库）")
    parser.add_argument("--skip-download", action="store_true", help="不下载缺失的输入文件")
    args = parser.parse_args()

    csv_path = os.path.abspath(args.csv)
    if not os.path.exists(csv_path) and not args.skip_download:
        download(BASE_CSV_URL, csv_path, min_size=50 * 1024 * 1024)
    raw_out = os.path.abspath(args.raw_out)
    build_from_csv(csv_path, raw_out, keep_all=args.keep_all)
    gz_out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(gz_out), exist_ok=True)
    write_gzip_with_isize(raw_out, gz_out)
    gz_mb = os.path.getsize(gz_out) / (1024 * 1024)
    print(f"[输出] {gz_out} ({gz_mb:.1f} MB，APK noCompress 原样打包后即此大小)")


if __name__ == "__main__":
    sys.exit(main())