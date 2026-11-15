#!/usr/bin/env python3
"""由 aapt2 link 的 symbols.txt 生成 app R.java 与每个 AAR 的 R.java。"""
import glob
import os
import re
import xml.etree.ElementTree as ET

WORK = os.path.expanduser("~/workspace/rs2241-build/work")
GEN = os.path.join(WORK, "gen")

# 解析最终符号表
symbols = {}          # (type, name) -> value str
styleables = {}       # name -> [attr values]
lines = open(os.path.join(WORK, "symbols.txt"), encoding="utf-8").read().splitlines()
for ln in lines:
    ln = ln.strip()
    m = re.match(r"int\[\]\s+styleable\s+(\S+)\s*\{(.*)\}", ln)
    if m:
        vals = [v.strip() for v in m.group(2).split(",") if v.strip()]
        styleables[m.group(1)] = vals
        continue
    m = re.match(r"int\s+(\S+)\s+(\S+)\s+(\S+)$", ln)
    if m:
        symbols[(m.group(1), m.group(2))] = m.group(3)


def emit_r(package, wanted):
    """wanted: set of (type,name) or None 表示全部。"""
    by_type = {}
    for (t, n), v in symbols.items():
        if wanted is not None and (t, n) not in wanted:
            continue
        by_type.setdefault(t, []).append((n, v))
    sty = {}
    for name, vals in styleables.items():
        if wanted is not None and ("styleable", name) not in wanted:
            continue
        sty[name] = vals
    if not by_type and not sty:
        return None
    out = [f"package {package};", "public final class R {"]
    for t in sorted(by_type):
        if t == "styleable":
            continue  # styleable 单独在下面生成（含数组与索引）
        out.append(f"  public static final class {t} {{")
        for n, v in sorted(by_type[t]):
            out.append(f"    public static final int {n} = {v};")
        out.append("  }")
    if sty:
        out.append("  public static final class styleable {")
        for name in sorted(sty):
            vals = ", ".join(sty[name])
            out.append(f"    public static final int[] {name} = new int[] {{ {vals} }};")
            # 索引字段来自 symbols 里的 ('styleable', 'Name_attr')
            for (t, n), v in sorted(symbols.items()):
                if t == "styleable" and n.startswith(name + "_"):
                    out.append(f"    public static final int {n} = {v};")
        out.append("  }")
    out.append("}")
    return "\n".join(out) + "\n"


os.makedirs(GEN, exist_ok=True)
# app R：全部符号
r = emit_r("com.rootsentinel", None)
d = os.path.join(GEN, "com/rootsentinel")
os.makedirs(d, exist_ok=True)
open(os.path.join(d, "R.java"), "w").write(r)
print("app R.java written")

# 各库 R：用其 R.txt 的名字集合求交
count = 0
for mf in sorted(glob.glob(os.path.join(WORK, "aar", "*", "AndroidManifest.xml"))):
    aar_dir = os.path.dirname(mf)
    rtxt = os.path.join(aar_dir, "R.txt")
    if not os.path.exists(rtxt):
        continue
    try:
        pkg = ET.parse(mf).getroot().get("package")
    except ET.ParseError:
        pkg = None
    if not pkg:
        continue
    wanted = set()
    for ln in open(rtxt, encoding="utf-8", errors="replace").read().splitlines():
        ln = ln.strip()
        m = re.match(r"int\[\]\s+styleable\s+(\S+)", ln) or re.match(r"int\s+(\S+)\s+(\S+)", ln)
        if ln.startswith("int[]"):
            mm = re.match(r"int\[\]\s+(\S+)\s+(\S+)", ln)
            if mm:
                wanted.add((mm.group(1), mm.group(2)))
        elif m and ln.startswith("int "):
            wanted.add((m.group(1), m.group(2)))
    r = emit_r(pkg, wanted)
    if r:
        d = os.path.join(GEN, *pkg.split("."))
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, "R.java"), "w").write(r)
        count += 1
print(f"lib R.java written: {count}")
