// rs_str.h — 编译期字符串混淆
//
// 用法：
//     std::string s = RSs("magisk");   // 可安全保存
//     const char* p = RS("magisk");    // 仅在当前完整表达式内有效
//
// 特性：
//  - 明文不出现在 .rodata 中（编译期已 XOR）
//  - key 随位置变化，同一字符在串中不同位置密文不同
#ifndef RS_STR_H
#define RS_STR_H

#include <cstddef>
#include <string>
#include <vector>

namespace rs {

constexpr char strKey(size_t i) {
    return (char)((0x3F + (i * 7)) ^ ((i & 0x07) << 3));
}

template <size_t N>
struct ObfLit {
    char d[N];
    constexpr ObfLit(const char (&s)[N]) : d{} {
        for (size_t i = 0; i < N; ++i) {
            d[i] = (char)(s[i] ^ strKey(i));
        }
    }
};

inline std::string decode(const char* enc, size_t n) {
    std::string out(n, '\0');
    for (size_t i = 0; i < n; ++i) {
        out[i] = (char)(enc[i] ^ strKey(i));
    }
    return out;
}

inline void secureZero(void* p, size_t n) {
    volatile unsigned char* v = (volatile unsigned char*)p;
    while (n--) *v++ = 0;
}

// 由 str_obf.cpp 提供
const std::vector<std::string>& hotKeywords();
const std::vector<std::string>& watchedLibs();

}  // namespace rs

// 返回 std::string（可安全跨语句保存）
#define RSs(s) (rs::decode(rs::ObfLit(s).d, sizeof(s) - 1))

// 返回 const char*（仅在当前完整表达式内有效，不要保存）
#define RS(s) (RSs(s).c_str())

#endif  // RS_STR_H