// json_builder.cpp — Json 类实现
#include "rs_json.h"

#include <cmath>
#include <cstdio>

namespace rs {

void Json::escape(const std::string& in, std::string& out) {
    out.reserve(out.size() + in.size() + 8);
    for (unsigned char c : in) {
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