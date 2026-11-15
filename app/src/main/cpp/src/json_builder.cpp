// json_builder.cpp — Json 类实现
#include "rs_json.h"

#include <cmath>
#include <cstdio>

namespace rs {

void Json::escape(const std::string& in, std::string& out) {
    out.reserve(out.size() + in.size() + 8);
    // v2.22.58（第九轮审计 P2-04）：多字节序列做严格 UTF-8 校验。
    // Native 检测会读文件名、系统属性、proc/sys 节点等外部数据，
    // 不保证是合法 UTF-8；非法字节若原样进 JSON，jni.cpp 的
    // NewStringUTF（要求 Modified UTF-8）可能转换失败甚至 abort。
    // 非法字节逐字节替换为 U+FFFD，合法序列原样保留。
    const size_t n = in.size();
    size_t i = 0;
    auto cont = [&](size_t j) {
        return j < n && ((unsigned char)in[j] & 0xC0) == 0x80;
    };
    while (i < n) {
        const unsigned char c = (unsigned char)in[i];
        if (c < 0x80) {
            switch (c) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n";  break;
                case '\r': out += "\\r";  break;
                case '\t': out += "\\t";  break;
                case '\b': out += "\\b";  break;
                case '\f': out += "\\f";  break;
                default:
                    if (c < 0x20) {
                        char tmp[8];
                        std::snprintf(tmp, sizeof(tmp), "\\u%04x", c);
                        out += tmp;
                    } else {
                        out += (char)c;
                    }
            }
            i += 1;
            continue;
        }
        size_t len = 0;
        if (c >= 0xC2 && c <= 0xDF) len = 2;
        else if (c >= 0xE0 && c <= 0xEF) len = 3;
        else if (c >= 0xF0 && c <= 0xF4) len = 4;
        bool ok = len > 0 && i + len <= n;
        if (ok) {
            const unsigned char c1 = (unsigned char)in[i + 1];
            if ((c1 & 0xC0) != 0x80) ok = false;
            else if (c == 0xE0 && c1 < 0xA0) ok = false;  // 过长编码
            else if (c == 0xED && c1 > 0x9F) ok = false;  // UTF-16 代理区
            else if (c == 0xF0 && c1 < 0x90) ok = false;  // 过长编码
            else if (c == 0xF4 && c1 > 0x8F) ok = false;  // 超出 U+10FFFF
            for (size_t j = 2; ok && j < len; j++) {
                if (!cont(i + j)) ok = false;
            }
        }
        if (ok) {
            out.append(in, i, len);
            i += len;
        } else {
            out += "\xEF\xBF\xBD";  // U+FFFD
            i += 1;
        }
    }
}

std::string quote(const std::string& s) {
    std::string out;
    out.push_back('"');
    Json::escape(s, out);
    out.push_back('"');
    return out;
}

void Json::sep() {
    if (afterKey_) {
        afterKey_ = false;
        return;
    }
    if (stack_.empty()) return;
    Frame& f = stack_.back();
    if (!f.first) buf_.push_back(',');
    f.first = false;
}

Json& Json::obj() {
    sep();
    buf_.push_back('{');
    stack_.push_back(Frame{false, true});
    return *this;
}

Json& Json::endObj() {
    if (!stack_.empty()) stack_.pop_back();
    buf_.push_back('}');
    return *this;
}

Json& Json::arr() {
    sep();
    buf_.push_back('[');
    stack_.push_back(Frame{true, true});
    return *this;
}

Json& Json::endArr() {
    if (!stack_.empty()) stack_.pop_back();
    buf_.push_back(']');
    return *this;
}

Json& Json::key(const std::string& k) {
    sep();
    buf_.push_back('"');
    escape(k, buf_);
    buf_.push_back('"');
    buf_.push_back(':');
    afterKey_ = true;
    return *this;
}

Json& Json::str(const std::string& v) {
    sep();
    buf_.push_back('"');
    escape(v, buf_);
    buf_.push_back('"');
    return *this;
}

Json& Json::num(double v) {
    sep();
    if (!std::isfinite(v)) {
        buf_ += "0";
        return *this;
    }
    char tmp[64];
    std::snprintf(tmp, sizeof(tmp), "%.6g", v);
    buf_ += tmp;
    return *this;
}

Json& Json::i64(int64_t v) {
    sep();
    buf_ += std::to_string(v);
    return *this;
}

Json& Json::u64(uint64_t v) {
    sep();
    buf_ += std::to_string(v);
    return *this;
}

Json& Json::boolean(bool v) {
    sep();
    buf_ += v ? "true" : "false";
    return *this;
}

Json& Json::nullv() {
    sep();
    buf_ += "null";
    return *this;
}

Json& Json::raw(const std::string& v) {
    sep();
    buf_ += v;
    return *this;
}

}  // namespace rs