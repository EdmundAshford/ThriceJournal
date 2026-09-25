# -*- coding: utf-8 -*-
"""
叁省手账 —— 下载内置字体的**开源原始文件**。

本仓库 `app/src/main/res/font/fNN.ttf` 里的字体全部来自 SIL OFL 1.1 授权的开源字体，
可自由使用与再分发（署名见仓库根 `THIRD_PARTY_FONTS.md`）。本脚本只负责把原始字体
拉到本地，随后交给 `tools/subset_fonts.py` 做子集化。

原始字体体积较大（合计约 110 MB），不进仓库；下载目录默认 `Fonts/`，已被 .gitignore 排除。

运行：
    python tools/fetch_open_fonts.py [输出目录]

依赖：Python 标准库（urllib），无需第三方包。
网络不通时可自行把下面 URL 列出的文件手动下载到输出目录，文件名保持一致即可。

注意：下面的文件名带 `NN_` 数字前缀，这是**刻意的**——`subset_fonts.py` 按文件名字典序
编号，前缀决定了 App 内字体选择器的排列顺序（黑体 → 站酷 → 楷体 → 手写）。
"""

import sys
import urllib.request
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
OUT_DIR = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else PROJECT_DIR / "Fonts"

# (保存文件名, 下载 URL, 大致体积 MB, 字体名, 授权)
FONTS = [
    ("01_NotoSansSC.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf",
     17, "Noto Sans SC", "SIL OFL 1.1"),
    ("02_ZCOOLQingKeHuangYou.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf",
     8, "ZCOOL QingKe HuangYou（站酷庆科黄油体）", "SIL OFL 1.1"),
    ("03_ZCOOLKuaiLe.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf",
     1, "ZCOOL KuaiLe（站酷快乐体）", "SIL OFL 1.1"),
    ("04_ZCOOLXiaoWei.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf",
     6, "ZCOOL XiaoWei（站酷小薇）", "SIL OFL 1.1"),
    ("05_LXGWWenKai-Regular.ttf",
     "https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Regular.ttf",
     24, "LXGW WenKai Regular（霞鹜文楷）", "SIL OFL 1.1"),
    ("06_LXGWWenKai-Light.ttf",
     "https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Light.ttf",
     27, "LXGW WenKai Light（霞鹜文楷 细）", "SIL OFL 1.1"),
    ("07_LXGWWenKaiScreen.ttf",
     "https://github.com/lxgw/LxgwWenKai-Screen/releases/download/v1.522/LXGWWenKaiScreen.ttf",
     24, "LXGW WenKai Screen（霞鹜文楷 屏幕阅读版）", "SIL OFL 1.1"),
    ("08_LXGWWenKaiMono-Regular.ttf",
     "https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKaiMono-Regular.ttf",
     24, "LXGW WenKai Mono Regular（霞鹜文楷 等宽）", "SIL OFL 1.1"),
    ("09_MaShanZheng.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/mashanzheng/MaShanZheng-Regular.ttf",
     6, "Ma Shan Zheng（马善政毛笔楷书）", "SIL OFL 1.1"),
    ("10_ZhiMangXing.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zhimangxing/ZhiMangXing-Regular.ttf",
     4, "Zhi Mang Xing（志莽行书）", "SIL OFL 1.1"),
    ("11_LongCang.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/longcang/LongCang-Regular.ttf",
     5, "Long Cang（龙藏体）", "SIL OFL 1.1"),
    ("12_LiuJianMaoCao.ttf",
     "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/liujianmaocao/LiuJianMaoCao-Regular.ttf",
     5, "Liu Jian Mao Cao（柳建毛草）", "SIL OFL 1.1"),
]

# jsDelivr / GitHub 在国内可能不稳，逐个尝试这些镜像前缀
JSDELIVR_MIRRORS = [
    "https://cdn.jsdelivr.net/gh/",
    "https://fastly.jsdelivr.net/gh/",
    "https://gcore.jsdelivr.net/gh/",
]
GITHUB_MIRRORS = [
    "https://github.com/",
    "https://gh-proxy.com/https://github.com/",
    "https://ghproxy.net/https://github.com/",
]


def candidates(url: str) -> list[str]:
    if url.startswith("https://cdn.jsdelivr.net/gh/"):
        rest = url[len("https://cdn.jsdelivr.net/gh/"):]
        return [m + rest for m in JSDELIVR_MIRRORS]
    if url.startswith("https://github.com/"):
        return [m + url[len("https://github.com/"):] for m in GITHUB_MIRRORS]
    return [url]


def download(name: str, url: str, min_mb: int) -> bool:
    dst = OUT_DIR / name
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=300) as resp, open(dst, "wb") as fp:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fp.write(chunk)
    size_mb = dst.stat().st_size / 1024 / 1024
    # 下载被截断是镜像最常见的问题，用体积下限兜底
    if size_mb < min_mb * 0.9:
        dst.unlink(missing_ok=True)
        raise RuntimeError(f"文件过小（{size_mb:.1f} MB < {min_mb} MB），疑为截断")
    return size_mb


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[INFO] 共 {len(FONTS)} 个字体，输出目录: {OUT_DIR}")
    ok = 0
    for name, url, min_mb, font_name, lic in FONTS:
        dst = OUT_DIR / name
        if dst.exists() and dst.stat().st_size / 1024 / 1024 >= min_mb * 0.9:
            print(f"[SKIP] {name}（已存在）")
            ok += 1
            continue
        done = False
        for u in candidates(url):
            try:
                size = download(name, u, min_mb)
                print(f"[OK]   {name}  {size:.1f} MB  <- {font_name} ({lic})")
                done = True
                ok += 1
                break
            except Exception as exc:  # noqa: BLE001
                print(f"[..]   {name} 经 {u.split('/')[2]} 失败：{exc}")
        if not done:
            print(f"[FAIL] {name} 所有镜像均失败，请手动下载：{url}")
    print(f"[DONE] 成功 {ok}/{len(FONTS)}。下一步：python tools/subset_fonts.py {OUT_DIR}")
    return 0 if ok == len(FONTS) else 1


if __name__ == "__main__":
    sys.exit(main())
