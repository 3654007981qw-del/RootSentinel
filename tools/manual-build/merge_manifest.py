#!/usr/bin/env python3
"""把各 AAR 清单里的组件/权限合并进 app 清单（等价于 AGP 的 manifest merger 子集）。"""
import copy
import glob
import os
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
ET.register_namespace("tools", "http://schemas.android.com/tools")
SRC = os.path.expanduser("~/workspace/rs2240-src/RootSentinel/app/src/main/AndroidManifest.xml")
WORK = os.path.expanduser("~/workspace/rs2241-build/work")
APPID = "com.rootsentinel"


def aname(el):
    return el.get(f"{{{ANDROID}}}name")


def fix_placeholders(el):
    for k, v in list(el.attrib.items()):
        if v and "${applicationId}" in v:
            el.set(k, v.replace("${applicationId}", APPID))
    for c in el:
        fix_placeholders(c)


app = ET.parse(SRC)
approot = app.getroot()
if "package" not in approot.attrib:
    approot.set("package", APPID)  # 原清单靠 AGP namespace 提供包名，手工构建需补上
app_el = approot.find("application")
seen_app_children = {(c.tag, aname(c)) for c in app_el}
seen_perms = {aname(c) for c in approot.findall("uses-permission")}

merged = 0
for mf in sorted(glob.glob(os.path.join(WORK, "aar", "*", "AndroidManifest.xml"))):
    try:
        lib = ET.parse(mf).getroot()
    except ET.ParseError:
        continue
    for perm in lib.findall("uses-permission"):
        if aname(perm) not in seen_perms:
            seen_perms.add(aname(perm))
            approot.append(copy.deepcopy(perm))
            merged += 1
    libapp = lib.find("application")
    if libapp is None:
        continue
    for child in libapp:
        fix_placeholders(child)
        key = (child.tag, aname(child))
        if key in seen_app_children:
            # 同名组件（如 androidx.startup 的 InitializationProvider）：
            # 把新贡献者的子元素（meta-data 等）并入已有元素，而不是丢弃
            existing = next(c for c in app_el if (c.tag, aname(c)) == key)
            have = {(c.tag, aname(c)) for c in existing}
            for sub in child:
                skey = (sub.tag, aname(sub))
                if skey not in have:
                    existing.append(copy.deepcopy(sub))
                    merged += 1
                    print(f"  sub-merge {key} <- {skey} (from {os.path.basename(os.path.dirname(mf))})")
            continue
        seen_app_children.add(key)
        app_el.append(copy.deepcopy(child))
        merged += 1
        print(f"  merge {key} (from {os.path.basename(os.path.dirname(mf))})")

out = os.path.join(WORK, "merged_manifest.xml")
app.write(out, encoding="utf-8", xml_declaration=True)
print(f"merged {merged} entries -> {out}")
