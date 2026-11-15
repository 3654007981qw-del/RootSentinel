#!/usr/bin/env bash
export PATH="$HOME/workspace/tools/jdk-21/bin:$PATH"
# RootSentinel v2.22.41 手工构建管线（改编自 v2.5.0 的 build_manual.sh）
# 阶段: prep res rjava native kotlin dex pkg verify
set -u
set -o pipefail
# v2.22.51 (audit round 7, #1): pipefail + per-stage hard failure gates.
# Previously kotlinc/D8 output ran through grep|head pipelines whose
# exit status masked compiler failure, and output dirs were never
# cleaned, so a failed or partial compile could ship stale classes.
SRC=$HOME/workspace/rs2240-src/RootSentinel
RB=$HOME/workspace/rs2241-build
DEPS=$RB/deps
WORK=$RB/work
SDK=$HOME/android-sdk
BT=$SDK/build-tools/35.0.1
AJAR=$SDK/platforms/android-36/android.jar
NDK=$SDK/ndk/27.2.12479018
JAVAC=/usr/lib/jvm/java-21-openjdk-amd64/bin/javac
JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
APPID=com.rootsentinel
VCODE=22252
VNAME=2.22.52
# 本次构建的签名身份：rsbuild 的 rs.jks（AURORA 侧密钥）。
# native 基线注入同一证书指纹，保证 I.APK_SIGNATURE 自洽。
SIGNER_SHA=06377C87CB33D9AB1B0BAE330135AB67404AB96DEEA9F3584DDF2B2FFCDC7A06
KS=$HOME/workspace/rsbuild/rs.jks
KSPASS=$HOME/workspace/rsbuild/.kspass
mkdir -p "$WORK"/{aar,jars,resz,gen,proguard}

stage_prep() {
  # 【版本单一事实源】Version.kt 的 NAME/CODE 与 CMakeLists 的
  # RS_VERSION 都由本脚本的 VNAME/VCODE 在此生成，不许手改。
  # 教训（2.22.47/48）：两处手写常量停在 2.22.46 没跟上，导致
  # 更新检查按假版本误报新版、真安装包又被版本校验拒绝（用户实测
  # 「更新失败：安装包版本不高于当前版本」）。生成失败立即中止。
  python3 - "$SRC/app/src/main/java/com/rootsentinel/Version.kt" \
             "$SRC/app/src/main/cpp/CMakeLists.txt" \
             "$SRC/app/build.gradle.kts" "$VNAME" "$VCODE" <<'PYEOF' || return 1
import re, sys
vkt, cmake, gradle, vname, vcode = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
s = open(vkt).read()
s2 = re.sub(r'const val NAME = "[0-9.]+"', f'const val NAME = "{vname}"', s)
s2 = re.sub(r'const val CODE = [0-9]+', f'const val CODE = {vcode}', s2)
assert s2 != s or f'"{vname}"' in s, "Version.kt pattern not found"
open(vkt, "w").write(s2)
c = open(cmake).read()
c2 = re.sub(r'RS_VERSION="[0-9.]+"', f'RS_VERSION="{vname}"', c)
assert c2 != c or f'RS_VERSION="{vname}"' in c, "CMakeLists RS_VERSION pattern not found"
open(cmake, "w").write(c2)
# v2.22.50: build.gradle.kts also generated (audit round 6, #1/#4) --
# it sat at 2.22.46 unmanaged; Gradle-path builds would drift again.
g = open(gradle).read()
g2 = re.sub(r'versionCode = [0-9]+', f'versionCode = {vcode}', g)
g2 = re.sub(r'versionName = "[0-9.]+"', f'versionName = "{vname}"', g2)
assert f'versionCode = {vcode}' in g2 and f'versionName = "{vname}"' in g2, \
    "build.gradle.kts pattern not found"
open(gradle, "w").write(g2)
print(f"version sources generated: {vname} / {vcode}")
PYEOF
  grep -q "const val NAME = \"$VNAME\"" "$SRC/app/src/main/java/com/rootsentinel/Version.kt" || { echo "VERSION GEN FAIL: Version.kt"; return 1; }
  grep -q "RS_VERSION=\"$VNAME\"" "$SRC/app/src/main/cpp/CMakeLists.txt" || { echo "VERSION GEN FAIL: CMakeLists"; return 1; }
  grep -q "versionName = \"$VNAME\"" "$SRC/app/build.gradle.kts" || { echo "VERSION GEN FAIL: build.gradle.kts"; return 1; }
  # 合并两个 deps 来源（rsbuild 的 AndroidX 集合 + 本次新拉的库），已在 deps/ 就绪
  for aar in "$DEPS"/*.aar; do
    name=$(basename "$aar" .aar)
    d="$WORK/aar/$name"
    if [ ! -d "$d" ]; then mkdir -p "$d"; unzip -q -o "$aar" -d "$d"; fi
    [ -f "$d/classes.jar" ] && cp "$d/classes.jar" "$WORK/jars/$name.jar"
    for j in "$d"/libs/*.jar; do [ -f "$j" ] && cp "$j" "$WORK/jars/${name}_$(basename "$j")"; done
    [ -f "$d/proguard.txt" ] && cp "$d/proguard.txt" "$WORK/proguard/$name.txt"
  done
  for j in "$DEPS"/*.jar; do
    case "$(basename "$j")" in
      *kotlin-compiler-embeddable*|*kotlin-compose-compiler*|*kotlin-reflect*) : ;;
      *) cp "$j" "$WORK/jars/" ;;
    esac
  done
  ls "$WORK/jars" | wc -l
}

stage_res() {
  rm -f "$WORK"/resz/*.zip
  "$BT/aapt2" compile --dir "$SRC/app/src/main/res" -o "$WORK/resz/app.zip" || return 1
  for d in "$WORK"/aar/*/; do
    name=$(basename "$d")
    if [ -d "$d/res" ] && [ -n "$(ls -A "$d/res" 2>/dev/null)" ]; then
      "$BT/aapt2" compile --dir "$d/res" -o "$WORK/resz/$name.zip" || echo "RESFAIL $name"
    fi
  done
  python3 "$RB/merge_manifest.py" || return 1
  local RARGS=()
  for z in "$WORK"/resz/*.zip; do
    [ "$z" = "$WORK/resz/app.zip" ] && continue
    RARGS+=(-R "$z")
  done
  "$BT/aapt2" link -o "$WORK/linked.apk" -I "$AJAR" \
    --manifest "$WORK/merged_manifest.xml" \
    --output-text-symbols "$WORK/symbols.txt" \
    --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 36 \
    --version-code $VCODE --version-name $VNAME \
    "$WORK/resz/app.zip" "${RARGS[@]}" || return 1
  ls -la "$WORK/linked.apk"
  python3 "$RB/gen_r.py" || return 1
  find "$WORK/gen" -name 'R.java' | wc -l
}

stage_rjava() {
  mkdir -p "$WORK/gen/com/rootsentinel"
  cat > "$WORK/gen/com/rootsentinel/BuildConfig.java" <<EOF
package com.rootsentinel;
public final class BuildConfig {
  public static final boolean DEBUG = false;
  public static final String APPLICATION_ID = "com.rootsentinel";
  public static final String BUILD_TYPE = "release";
  public static final int VERSION_CODE = $VCODE;
  public static final String VERSION_NAME = "$VNAME";
}
EOF
  "$JAVAC" -nowarn -cp "$AJAR" -d "$WORK/r-classes" $(find "$WORK/gen" -name '*.java') || return 1
  find "$WORK/r-classes" -name 'R*.class' | wc -l
}

stage_native() {
  for abi in arm64-v8a armeabi-v7a x86_64; do
    b="$WORK/native/$abi"
    extra=""
    [ "$abi" = "armeabi-v7a" ] && extra="-DANDROID_ARM_MODE=arm"
    "$SDK/cmake/3.22.1/bin/cmake" -S "$SRC/app/src/main/cpp" -B "$b" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 $extra \
      -DRS_SIGNER_SHA256=$SIGNER_SHA \
      -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM="$SDK/cmake/3.22.1/bin/ninja" -G Ninja >/dev/null || return 1
    "$SDK/cmake/3.22.1/bin/cmake" --build "$b" -j2 || return 1
    ls -la "$b"/librootsentinel.so
  done
}

stage_kotlin() {
  local CP="$AJAR:$WORK/r-classes"
  for j in "$WORK"/jars/*.jar; do CP="$CP:$j"; done
  find "$SRC/app/src/main/java" -name '*.kt' > "$WORK/sources.txt"
  wc -l < "$WORK/sources.txt"
  rm -rf "$WORK/kotlin-classes"
  mkdir -p "$WORK/kotlin-classes"
  "$JAVA" -Xmx1400m -cp "$DEPS/kotlin-compiler-embeddable-2.2.20.jar:$DEPS/kotlin-stdlib-2.3.20.jar:$DEPS/kotlin-reflect-2.2.20.jar:$WORK/jars/org.jetbrains.kotlinx_kotlinx-coroutines-core-jvm_1.9.0.jar:$DEPS/org.jetbrains.intellij.deps_trove4j_1.0.20200330.jar:$DEPS/org.jetbrains_annotations_23.0.0.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$CP" -d "$WORK/kotlin-classes" \
    -Xplugin="$DEPS/kotlin-compose-compiler-plugin-embeddable-2.2.20.jar" \
    -jvm-target 17 -no-stdlib -no-reflect \
    -Xcontext-parameters -Xskip-metadata-version-check \
    -opt-in=androidx.compose.material3.ExperimentalMaterial3Api \
    -opt-in=androidx.compose.foundation.ExperimentalFoundationApi \
    -opt-in=androidx.compose.animation.ExperimentalAnimationApi \
    -opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi \
    @"$WORK/sources.txt" 2>&1 | tee "$WORK/kotlinc.log" | grep -E ': error:' | head -40
  local errs
  errs=$(grep -c ': error:' "$WORK/kotlinc.log" || true)
  echo "errors=$errs"
  if [ "$errs" != "0" ]; then echo "KOTLIN FAILED: $errs compile errors"; return 1; fi
  if [ -z "$(find "$WORK/kotlin-classes" -name '*.class' -print -quit)" ]; then
    echo "KOTLIN FAILED: no classes produced (compiler crash?)"; return 1
  fi
}

stage_dex() {
  mkdir -p "$WORK/appjar-content"
  rm -rf "$WORK/appjar-content/com" "$WORK/appjar-content/androidx"
  cp -r "$WORK/kotlin-classes/." "$WORK/appjar-content/"
  cp -r "$WORK/r-classes/." "$WORK/appjar-content/"
  rm -f "$WORK/app.jar"
  /usr/lib/jvm/java-21-openjdk-amd64/bin/jar --create --file "$WORK/app.jar" -C "$WORK/appjar-content" .
  rm -rf "$WORK/dex-release"
  mkdir -p "$WORK/dex-release"
  local INPUTS=("$WORK/app.jar")
  for j in "$WORK"/jars/*.jar; do INPUTS+=("$j"); done
  "$JAVA" -Xmx1200m -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 26 --lib "$AJAR" --output "$WORK/dex-release" "${INPUTS[@]}" > "$WORK/d8.log" 2>&1 || { echo "D8 FAILED"; tail -20 "$WORK/d8.log"; return 1; }
  grep -vE '^Warning in .* (program|library)' "$WORK/d8.log" | head -20 || true
  [ -f "$WORK/dex-release/classes.dex" ] || { echo "D8 FAILED: no classes.dex"; return 1; }
  ls -la "$WORK/dex-release"
}

stage_pkg() {
  rm -f "$WORK/rs-release.apk"
  cp "$WORK/linked.apk" "$WORK/rs-release.apk"
  python3 "$RB/package_apk.py" release || return 1
  "$BT/zipalign" -f -p 4 "$WORK/rs-release.apk" "$WORK/rs-release-aligned.apk" || return 1
  "$BT/apksigner" sign --ks "$KS" --ks-pass file:"$KSPASS" \
    --out "$WORK/RootSentinel-v$VNAME-release.apk" "$WORK/rs-release-aligned.apk" || return 1
  ls -la "$WORK/RootSentinel-v$VNAME-release.apk"
  stage_gate "$WORK/RootSentinel-v$VNAME-release.apk" || return 1
}

stage_gate() {
  # Ship gate (round 7 #1): the audits that used to be manual steps,
  # now enforced in-script before any release leaves the pipeline.
  local apk="$1"
  [ -f "$apk" ] || { echo "GATE FAIL: no apk"; return 1; }
  local badging
  badging=$("$BT/aapt2" dump badging "$apk") || { echo "GATE FAIL: badging run"; return 1; }
  grep -q "versionCode='$VCODE' versionName='$VNAME'" <<<"$badging" || { echo "GATE FAIL: badging version"; return 1; }
  "$BT/apksigner" verify "$apk" >/dev/null || { echo "GATE FAIL: signature"; return 1; }
  local tmp; tmp=$(mktemp -d)
  unzip -o -q "$apk" 'classes*.dex' 'lib/*/librootsentinel.so' -d "$tmp" || { echo "GATE FAIL: unzip"; rm -rf "$tmp"; return 1; }
  [ -f "$tmp/classes.dex" ] || { echo "GATE FAIL: no classes.dex in apk"; rm -rf "$tmp"; return 1; }
  local stale
  stale=$(cat "$tmp"/classes*.dex | strings | grep -oE '2\.22\.[0-9]+' | sort -u | grep -vx "$VNAME" || true)
  [ -z "$stale" ] || { echo "GATE FAIL: stale version strings in dex: $stale"; rm -rf "$tmp"; return 1; }
  local abi vs
  for abi in arm64-v8a x86_64; do
    vs=$(strings "$tmp/lib/$abi/librootsentinel.so" | grep -oE '2\.22\.[0-9]+' | sort -u)
    [ "$vs" = "$VNAME" ] || { echo "GATE FAIL: $abi .so version strings: $vs"; rm -rf "$tmp"; return 1; }
  done
  rm -rf "$tmp"
  echo "GATE OK: badging/signature/dex/.so all at $VNAME"
}

stage_verify() {
  "$BT/apksigner" verify --print-certs "$1" | grep -i 'SHA-256'
  unzip -l "$1" | grep -E 'classes.*\.dex|lib/.*\.so|resources.arsc|AndroidManifest'
}

stage_debug() {
  # Debug 变体（v2.22.52 起固化）：与 release 只差两处——manifest 的
  # android:debuggable=true 与 BuildConfig.DEBUG=true/BUILD_TYPE=debug。
  # Kotlin 类、资源、Native 全部与 release 共用，保证 debug 包测到的
  # 就是 release 的代码。此前各版靠临时手工拼装，做法没留档、还出过
  # dex 与 release 逐字节相同（DEBUG 没翻）的事故，故固化为阶段。
  mkdir -p "$WORK/gen-debug/com/rootsentinel" "$WORK/r-classes-debug"
  cat > "$WORK/gen-debug/com/rootsentinel/BuildConfig.java" <<EOF
package com.rootsentinel;
public final class BuildConfig {
  public static final boolean DEBUG = true;
  public static final String APPLICATION_ID = "com.rootsentinel";
  public static final String BUILD_TYPE = "debug";
  public static final int VERSION_CODE = $VCODE;
  public static final String VERSION_NAME = "$VNAME";
}
EOF
  "$JAVAC" -nowarn -cp "$AJAR" -d "$WORK/r-classes-debug" \
    "$WORK/gen-debug/com/rootsentinel/BuildConfig.java" || return 1
  rm -rf "$WORK/appjar-debug"
  mkdir -p "$WORK/appjar-debug"
  cp -r "$WORK/kotlin-classes/." "$WORK/appjar-debug/"
  cp -r "$WORK/r-classes/." "$WORK/appjar-debug/"
  cp "$WORK/r-classes-debug/com/rootsentinel/BuildConfig.class" \
     "$WORK/appjar-debug/com/rootsentinel/BuildConfig.class" || return 1
  rm -f "$WORK/app-debug.jar"
  /usr/lib/jvm/java-21-openjdk-amd64/bin/jar --create \
    --file "$WORK/app-debug.jar" -C "$WORK/appjar-debug" . || return 1
  rm -rf "$WORK/dex-debug"
  mkdir -p "$WORK/dex-debug"
  local INPUTS=("$WORK/app-debug.jar")
  local j
  for j in "$WORK"/jars/*.jar; do INPUTS+=("$j"); done
  "$JAVA" -Xmx1200m -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 26 --lib "$AJAR" --output "$WORK/dex-debug" \
    "${INPUTS[@]}" > "$WORK/d8-debug.log" 2>&1 \
    || { echo "D8 FAILED (debug)"; tail -20 "$WORK/d8-debug.log"; return 1; }
  [ -f "$WORK/dex-debug/classes.dex" ] || { echo "D8 FAILED: no debug classes.dex"; return 1; }
  python3 - "$WORK/merged_manifest.xml" "$WORK/merged_manifest_debug.xml" <<'PYEOF' || return 1
import sys
s = open(sys.argv[1]).read()
assert 'android:debuggable' not in s, "manifest already has debuggable"
s2 = s.replace('<application ', '<application android:debuggable="true" ', 1)
assert s2 != s, "application tag not found"
open(sys.argv[2], "w").write(s2)
print("debug manifest written")
PYEOF
  local RARGS=()
  local z
  for z in "$WORK"/resz/*.zip; do
    [ "$z" = "$WORK/resz/app.zip" ] && continue
    RARGS+=(-R "$z")
  done
  "$BT/aapt2" link -o "$WORK/linked-debug.apk" -I "$AJAR" \
    --manifest "$WORK/merged_manifest_debug.xml" \
    --output-text-symbols "$WORK/symbols-debug.txt" \
    --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 36 \
    --version-code $VCODE --version-name $VNAME \
    "$WORK/resz/app.zip" "${RARGS[@]}" || return 1
  rm -f "$WORK/rs-debug.apk"
  cp "$WORK/linked-debug.apk" "$WORK/rs-debug.apk"
  python3 "$RB/package_apk.py" debug || return 1
  "$BT/zipalign" -f -p 4 "$WORK/rs-debug.apk" "$WORK/rs-debug-aligned.apk" || return 1
  "$BT/apksigner" sign --ks "$KS" --ks-pass file:"$KSPASS" \
    --out "$WORK/RootSentinel-v$VNAME-debug.apk" "$WORK/rs-debug-aligned.apk" || return 1
  ls -la "$WORK/RootSentinel-v$VNAME-debug.apk"
  stage_gate "$WORK/RootSentinel-v$VNAME-debug.apk" || return 1
  # 注意：badging 输出必须先捕获再 grep——pipefail 下 grep -q 提前
  # 退出会让 aapt2 吃 SIGPIPE、管道返回非零，误报 GATE FAIL
  # （2.22.51 构建时 stage_gate 已踩过一次）。
  local dbg_badging
  dbg_badging=$("$BT/aapt2" dump badging "$WORK/RootSentinel-v$VNAME-debug.apk") || return 1
  grep -q 'application-debuggable' <<<"$dbg_badging" \
    || { echo "GATE FAIL: debug apk is not debuggable"; return 1; }
  echo "DEBUG OK: debuggable + BuildConfig.DEBUG=true at $VNAME"
}

case "${1:-}" in
  prep) stage_prep ;;
  res) stage_res ;;
  rjava) stage_rjava ;;
  native) stage_native ;;
  kotlin) stage_kotlin ;;
  dex) stage_dex ;;
  pkg) stage_pkg ;;
  debug) stage_debug ;;
  verify) stage_verify "${2:-}" ;;
  gate) stage_gate "${2:-}" ;;
  *) echo "usage: $0 prep|res|rjava|native|kotlin|dex|pkg|debug|verify <apk>" ;;
esac
