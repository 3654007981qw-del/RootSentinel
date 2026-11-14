// str_obf.cpp — 特征串与受监控库列表
//
// 集中管理所有会被全文匹配的关键字，避免明文散落到各个编译单元。
#include "rs_str.h"

namespace rs {

const std::vector<std::string>& hotKeywords() {
    static const std::vector<std::string> kw = {
        RSs("magisk"),
        RSs("kernelsu"),
        RSs("zygisk"),
        RSs("lsposed"),
        RSs("frida"),
        RSs("apatch"),
        RSs("riru"),
        RSs("shamiko"),
        RSs("susfs"),
        RSs("nomount"),
        RSs("gum-js-loop"),
        RSs("xposed"),
    };
    return kw;
}

const std::vector<std::string>& watchedLibs() {
    static const std::vector<std::string> libs = {
        RSs("libc.so"),
        RSs("libdl.so"),
        RSs("libart.so"),
        RSs("linker64"),
        RSs("linker"),
        RSs("libandroid_runtime.so"),
    };
    return libs;
}

}  // namespace rs