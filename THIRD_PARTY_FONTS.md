# Third-party font attribution / 第三方字体署名

All fonts bundled at `app/src/main/res/font/fNN.ttf` are open-source fonts licensed under the
**SIL Open Font License 1.1** — free to use, modify and redistribute. This file satisfies OFL
condition 2 ("each copy must include the copyright notice and this license").

> 中文：仓库内全部字体均为 SIL OFL 1.1 开源字体，可自由使用、修改与再分发。本文件满足 OFL 第 2 条要求。

## Important: these are MODIFIED versions

The `fNN.ttf` files in this repo are **subsets** produced by `tools/subset_fonts.py`
(ASCII + GB2312 codepoints only; font names stripped from the `name` table; hinting and
OpenType layout tables removed). Per OFL condition 3, modified versions may not use the
original Reserved Font Names, therefore:

- the app UI shows only numbers **01–12**, never the original font names;
- the `name` table was emptied during subsetting, so the files themselves no longer carry
  the original names.

The original (unmodified) fonts can be fetched via `python tools/fetch_open_fonts.py`.

## Font list / 字体清单

| # | Font | Copyright / Reserved Font Name | License | Source |
|---|---|---|---|---|
| 01 | Noto Sans SC | Copyright 2014–2021 Adobe, RFN "Source" | SIL OFL 1.1 | `google/fonts` → `ofl/notosanssc` |
| 02 | ZCOOL QingKe HuangYou | Copyright ZCOOL, RFN "ZCOOL QingKe HuangYou" | SIL OFL 1.1 | `google/fonts` → `ofl/zcoolqingkehuangyou` |
| 03 | ZCOOL KuaiLe | Copyright ZCOOL, RFN "ZCOOL KuaiLe" | SIL OFL 1.1 | `google/fonts` → `ofl/zcoolkuaile` |
| 04 | ZCOOL XiaoWei | Copyright ZCOOL, RFN "ZCOOL XiaoWei" | SIL OFL 1.1 | `google/fonts` → `ofl/zcoolxiaowei` |
| 05 | LXGW WenKai Regular | Copyright LXGW, RFN "LXGW WenKai" | SIL OFL 1.1 | `lxgw/LxgwWenKai` v1.522 |
| 06 | LXGW WenKai Light | as above | SIL OFL 1.1 | `lxgw/LxgwWenKai` v1.522 |
| 07 | LXGW WenKai Screen | as above | SIL OFL 1.1 | `lxgw/LxgwWenKai-Screen` v1.522 |
| 08 | LXGW WenKai Mono Regular | as above | SIL OFL 1.1 | `lxgw/LxgwWenKai` v1.522 |
| 09 | Ma Shan Zheng | Copyright Google / 马善政, RFN "Ma Shan Zheng" | SIL OFL 1.1 | `google/fonts` → `ofl/mashanzheng` |
| 10 | Zhi Mang Xing | Copyright Google / 志莽, RFN "Zhi Mang Xing" | SIL OFL 1.1 | `google/fonts` → `ofl/zhimangxing` |
| 11 | Long Cang | Copyright Google / 龙藏, RFN "Long Cang" | SIL OFL 1.1 | `google/fonts` → `ofl/longcang` |
| 12 | Liu Jian Mao Cao | Copyright Google / 柳建, RFN "Liu Jian Mao Cao" | SIL OFL 1.1 | `google/fonts` → `ofl/liujianmaocao` |

Number → filename mapping is maintained by `tools/font_map.md` (script-generated).

## SIL OPEN FONT LICENSE Version 1.1

```
PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

Full license text: <https://scripts.sil.org/OFL>

## History note / 历史说明

Early internal builds bundled subsets of 32 **commercially licensed** fonts (漢儀, 方正, 文鼎, 华康, etc.).
Redistribution of such fonts is not permitted, and subsetting does not remove traceability
(glyph outlines and `OS/2.achVendID` still identify the foundry). That batch and all of its
source files have been deleted and replaced with the 12 OFL fonts listed above.

> 中文：早期版本曾内置 32 款商业授权字体（漢儀、方正、文鼎、华康等）的子集。这类字体不允许再分发，
> 且子集化无法去除可追溯性（字形轮廓不变、`OS/2.achVendID` 仍指向字厂）。该批字体及其源文件已全部删除，
> 替换为上表 12 款 OFL 开源字体。
