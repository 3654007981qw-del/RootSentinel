package com.rootsentinel

/**
 * 版本常量（v2.5.0）
 *
 * ⚠️ 本文件的 NAME/CODE 由构建管线 build_apk.sh 的 stage_prep
 * 按脚本里的 VNAME/VCODE 自动生成——不要手改这里的数字。
 * （2.22.47/48 曾因手改漏跟而漂移：常量停在 2.22.46，更新检查
 * 误报新版、安装又被版本校验拒绝。CMakeLists 的 RS_VERSION
 * 同理由管线生成，两边永远一致。）
 */
object RootSentinelVersion {
    const val NAME = "2.22.54"
    const val CODE = 22254
}