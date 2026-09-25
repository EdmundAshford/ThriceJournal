# -*- coding: utf-8 -*-
"""
叁省手账 - 内置字体编号化与子集化脚本

规则：
1. 源目录下的全部 .ttf/.TTF/.otf，按文件名（不区分大小写）字典序编号 f01..fNN；
   因此源文件名通常带 `NN_` 数字前缀，用以决定 App 内字体选择器的排列顺序；
2. 用户界面只展示编号，不展示字体名——这既是产品选择，也是 OFL「保留字体名」
   条款对修改版本的要求（见仓库根 THIRD_PARTY_FONTS.md）；
3. 每个字体只保留 ASCII + GB2312 全部码位 + 常用中文标点/符号，大幅压缩体积；
4. 输出到 app/src/main/res/font/fNN.ttf（Android 资源名须字母开头、全小写）；
5. 编号映射表写入 tools/font_map.md，随仓库分发。

输入源由 tools/fetch_open_fonts.py 下载（默认目录项目根下的 Fonts/）：

    python tools/fetch_open_fonts.py        # 下载原始开源字体（OFL）
    python tools/subset_fonts.py            # 子集化到 res/font

仓库中只使用 SIL OFL 1.1 等**允许再分发**的字体；任何商业授权字体
（漢儀 / 方正 / 文鼎 / 华康 等）都不得进入本目录——子集化不会改变字形轮廓，
无法消除侵权可追溯性。

依赖：fonttools >= 4.40（pip install fonttools）
"""

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

PROJECT_DIR = Path(__file__).resolve().parents[1]
SRC_DIR = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else PROJECT_DIR / "Fonts"
OUT_DIR = PROJECT_DIR / "app" / "src" / "main" / "res" / "font"
MAP_FILE = Path(__file__).resolve().parent / "font_map.md"

FONT_EXTS = {".ttf", ".otf", ".ttc"}


def build_keep_codepoints() -> set[int]:
    cps: set[int] = set()

    # 1) ASCII 可打印 + 常用控制（保留 0x09/0x0A/0x0D）
    cps.update(range(0x20, 0x7F))
    cps.update({0x09, 0x0A, 0x0D})

    # 2) GB2312 全部码位（一级/二级汉字 + 中文标点、全角符号、希腊/俄文/注音等）
    for b1 in range(0xA1, 0xF8):
        for b2 in range(0xA1, 0xFF):
            try:
                ch = bytes([b1, b2]).decode("gb2312")
                cps.add(ord(ch))
            except (UnicodeDecodeError, ValueError):
                pass

    # 3) 常用排版/界面符号（GB2312 未覆盖的部分）
    cps.update(range(0x2010, 0x2028))   # 弯引号、长短破折号、省略号 …
    cps.add(0x2026)
    cps.add(0x2030)                     # ‰
    cps.add(0x20AC)                     # €
    cps.update(range(0x2190, 0x219A))   # 箭头
    cps.update(range(0x2460, 0x24FF))   # 带圈数字/字母
    cps.update(range(0x25A0, 0x2600))   # 几何图形 ●★前半
    cps.update(range(0x2600, 0x2607))   # ☀☁☂☃☄★☆
    cps.add(0x263A)                     # ☺
    cps.update(range(0x3000, 0x303F))   # CJK 符号和标点
    cps.update(range(0xFF00, 0xFFF0))   # 全角 ASCII / 半角片假名
    return cps


def drop_bad_cmap_subtables(font: TTFont, src_name: str) -> None:
    """个别第三方字体的 cmap format-4 子表本身越界，编译即报错；
    预先试编译并丢弃损坏子表（保留其余可用 Unicode 子表）。"""
    if "cmap" not in font:
        return
    good = []
    for table in font["cmap"].tables:
        try:
            # 触发反编译（format-4 越界断言在 decompile 阶段就会抛出）
            _ = table.cmap
            good.append(table)
        except Exception as exc:  # noqa: BLE001
            print(f"[WARN] {src_name}: 丢弃损坏 cmap 子表 "
                  f"(platform={table.platformID}, platEnc={table.platEncID}, fmt={table.format}): {exc}")
    font["cmap"].tables = good


def parse_format4_cmap_from_disk(src: Path) -> dict[int, int]:
    """直接解析磁盘上 sfnt 文件中的 format-4 cmap（容错版）：
    损坏字体的越界段直接跳过，返回 {codepoint: glyphId}。"""
    import struct

    with open(src, "rb") as fp:
        data = fp.read()
    num_tables = struct.unpack(">H", data[4:6])[0]
    cmap_raw = None
    pos = 12
    for _ in range(num_tables):
        tag, _checksum, offset, length = struct.unpack(">4sIII", data[pos:pos + 16])
        pos += 16
        if tag == b"cmap":
            cmap_raw = data[offset:offset + length]
            break
    if cmap_raw is None:
        return {}

    _version, n_sub = struct.unpack(">HH", cmap_raw[0:4])
    candidates: list[tuple[int, int]] = []  # (priority, subtable_offset)
    rec = 4
    for _ in range(n_sub):
        platform, enc, off = struct.unpack(">HHI", cmap_raw[rec:rec + 8])
        rec += 8
        priority = {(3, 1): 0, (0, 3): 1, (0, 0): 2}.get((platform, enc), 9)
        candidates.append((priority, off))
    candidates.sort()

    for _priority, off in candidates:
        fmt, _length, _lang = struct.unpack(">HHH", cmap_raw[off:off + 6])
        if fmt != 4:
            continue
        seg_count = struct.unpack(">H", cmap_raw[off + 6:off + 8])[0] // 2
        p = off + 14
        ends = list(struct.unpack(f">{seg_count}H", cmap_raw[p:p + 2 * seg_count]))
        p += 2 * seg_count + 2  # skip reservedPad
        starts = list(struct.unpack(f">{seg_count}H", cmap_raw[p:p + 2 * seg_count]))
        p += 2 * seg_count
        deltas = list(struct.unpack(f">{seg_count}h", cmap_raw[p:p + 2 * seg_count]))
        p += 2 * seg_count
        range_offs_pos = p
        ranges = list(struct.unpack(f">{seg_count}H", cmap_raw[p:p + 2 * seg_count]))
        p += 2 * seg_count
        glyph_array = cmap_raw[p:]

        mapping: dict[int, int] = {}
        for seg in range(seg_count):
            start, end = starts[seg], ends[seg]
            if start == 0xFFFF and end == 0xFFFF:
                continue
            delta = deltas[seg]
            ro = ranges[seg]
            for cp in range(start, end + 1):
                if ro == 0:
                    gid = (cp + delta) & 0xFFFF
                    if gid:
                        mapping[cp] = gid
                else:
                    # offset 相对该 idRangeOffset 字段自身位置
                    idx_loc = (range_offs_pos + seg * 2) + 2 * (cp - start) + ro
                    arr_idx = (idx_loc - p) // 2
                    if 0 <= arr_idx < len(glyph_array) // 2:
                        gid = struct.unpack(
                            ">H", glyph_array[arr_idx * 2:arr_idx * 2 + 2]
                        )[0]
                        if gid:
                            mapping[cp] = gid
        if mapping:
            return mapping
    return {}


def rebuild_cmap_from_gid_map(font: TTFont, gid_map: dict[int, int]) -> None:
    """post 3.0 无字形名 + cmap 全毁时：按 GID 造一套合成字形名并替换 cmap。"""
    from fontTools.ttLib.tables._c_m_a_p import CmapSubtable, table__c_m_a_p

    num_glyphs = int(font["maxp"].numGlyphs)
    order = [f"gid{i:05d}" for i in range(num_glyphs)]
    order[0] = ".notdef"
    cmap_data: dict[int, str] = {}
    used_names: set[str] = set(order)
    for cp, gid in gid_map.items():
        if not (0 <= gid < num_glyphs):
            continue
        candidate = f"u{cp:04X}"
        if candidate in used_names and order[gid] != candidate:
            candidate = f"u{cp:04X}_g{gid}"
        if order[gid].startswith("gid"):
            order[gid] = candidate
        used_names.add(candidate)
        cmap_data[cp] = order[gid]
    font.setGlyphOrder(order)

    st = CmapSubtable.newSubtable(4)
    st.platformID = 3
    st.platEncID = 1
    st.language = 0
    st.cmap = cmap_data
    cmap = table__c_m_a_p()
    cmap.tableVersion = 0
    cmap.tables = [st]
    font["cmap"] = cmap


def subset_one(src: Path, dst: Path, unicodes: list[int]) -> int:
    font = TTFont(str(src), lazy=True, fontNumber=0)
    drop_bad_cmap_subtables(font, src.name)
    # 所有 cmap 子表都损坏（如「经典魏碑简」post3.0）：从磁盘原始字节容错重建
    if not font["cmap"].tables:
        gid_map = parse_format4_cmap_from_disk(src)
        print(f"[WARN] {src.name}: cmap 子表全部损坏，磁盘容错解析恢复 {len(gid_map)} 个码位映射")
        rebuild_cmap_from_gid_map(font, gid_map)
    options = subset.Options()
    options.glyph_names = False
    options.name_IDs = []              # 去除 name 表中的商业名（编号化更彻底）
    options.name_legacy = False
    options.name_lookup = []
    options.layout_features = []       # 去除连字/定位等 GSUB/GPOS（本应用纯展示）
    options.desubroutinize = True
    options.notdef_outline = True
    options.recalc_bounds = True
    options.hinting = False          # 去掉 hinting 指令（移动端不依赖，明显减小体积）
    options.drop_tables += ["DSIG", "GSUB", "GPOS", "GDEF", "BASE", "JSTF",
                            "fpgm", "prep", "cvt ", "VORG", "morx", "kern"]
    options.ignore_missing_glyphs = True

    sub = subset.Subsetter(options=options)
    sub.populate(unicodes=unicodes)
    sub.subset(font)
    font.flavor = None
    dst.parent.mkdir(parents=True, exist_ok=True)
    try:
        font.save(dst)
    except Exception as exc:  # noqa: BLE001
        # 源字体 cmap 元数据损坏（如「经典魏碑简」），subset 后 format-4 编译越界；
        # 丢弃其 cmap，用 subsetPlan 保留下来的 unicode→glyph 映射重建全新 format-4。
        print(f"[WARN] {src.name}: 原 cmap 编译失败（{exc}），重建 cmap 后重试")
        rebuild_cmap(font, sub)
        font.save(dst)
    font.close()
    return dst.stat().st_size


def rebuild_cmap(font: TTFont, sub: subset.Subsetter) -> None:
    from fontTools.ttLib.tables._c_m_a_p import CmapSubtable, table__c_m_a_p

    glyph_order = font.getGlyphOrder()
    data: dict[int, int] = {}
    for glyph_name, uni_set in getattr(sub.subset_plan, "glyphs_unicodes", {}).items():
        if glyph_name not in glyph_order:
            continue
        gid = font.getGlyphID(glyph_name)
        for uni in uni_set:
            if uni <= 0xFFFF:
                data[uni] = gid
    st = CmapSubtable.newSubtable(4)
    st.platformID = 3
    st.platEncID = 3
    st.language = 0
    st.cmap = data
    cmap = table__c_m_a_p()
    cmap.tableVersion = 0
    cmap.tables = [st]
    font["cmap"] = cmap


def main() -> int:
    if not SRC_DIR.is_dir():
        print(f"[ERR] 源字体目录不存在: {SRC_DIR}")
        return 1

    files = sorted(
        (p for p in SRC_DIR.iterdir() if p.suffix.lower() in FONT_EXTS),
        key=lambda p: p.name.casefold(),
    )
    if not files:
        print(f"[ERR] 目录下未找到字体文件: {SRC_DIR}")
        return 1

    cps = build_keep_codepoints()
    unicodes = sorted(cps)
    print(f"[INFO] 发现 {len(files)} 个字体；保留码位 {len(unicodes)} 个")
    print(f"[INFO] 输出目录: {OUT_DIR}")

    rows = []
    total = 0
    for idx, src in enumerate(files, start=1):
        num = f"{idx:02d}"
        dst = OUT_DIR / f"f{num}.ttf"
        try:
            size = subset_one(src, dst, unicodes)
        except Exception as exc:  # noqa: BLE001
            print(f"[FAIL] {num} <- {src.name}: {exc}")
            return 2
        total += size
        rows.append((num, src.name, size))
        print(f"[OK] f{num}.ttf <- {src.name}  ({size / 1024 / 1024:.2f} MB)")

    MAP_FILE.write_text(
        "# 字体编号映射（由 tools/subset_fonts.py 自动生成）\n\n"
        "源目录由 tools/fetch_open_fonts.py 下载，按文件名不区分大小写字典序编号；"
        "输出 `app/src/main/res/font/fNN.ttf`。\n"
        "字体名与授权署名见仓库根 `THIRD_PARTY_FONTS.md`；App 界面只展示编号。\n\n"
        "| 编号 | 原始文件 | 子集化后大小 |\n|---|---|---|\n"
        + "\n".join(f"| f{n} | {name} | {size / 1024 / 1024:.2f} MB |" for n, name, size in rows)
        + f"\n\n**合计 {total / 1024 / 1024:.1f} MB**\n",
        encoding="utf-8",
    )
    print(f"[DONE] 共 {len(files)} 个，合计 {total / 1024 / 1024:.1f} MB；映射表: {MAP_FILE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
