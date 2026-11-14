// rs_json.h — 无第三方依赖的 JSON 构造器
#ifndef RS_JSON_H
#define RS_JSON_H

#include <cstdint>
#include <string>
#include <vector>

namespace rs {

class Json {
public:
    Json& obj();
    Json& endObj();
    Json& arr();
    Json& endArr();
    Json& key(const std::string& k);
    Json& str(const std::string& v);
    Json& num(double v);
    Json& i64(int64_t v);
    Json& u64(uint64_t v);
    Json& boolean(bool v);
    Json& nullv();
    Json& raw(const std::string& v);

    Json& kv(const std::string& k, const std::string& v) { return key(k).str(v); }
    // 关键：必须显式提供 const char* 重载。
    // 否则 const char* 会被「标准转换」优先转成 bool（优先于 std::string 的用户定义转换），
    // 导致 severityName()/verdictName() 之类的返回值被写成 true/false。
    Json& kv(const std::string& k, const char* v)       { return key(k).str(v ? std::string(v) : std::string()); }
    Json& kv(const std::string& k, int64_t v)            { return key(k).i64(v); }
    Json& kv(const std::string& k, int v)                { return key(k).i64(v); }
    Json& kv(const std::string& k, bool v)               { return key(k).boolean(v); }

    const std::string& toString() const { return buf_; }
    std::string take() { return std::move(buf_); }
    bool empty() const { return buf_.empty(); }

private:
    struct Frame {
        bool isArray = false;
        bool first   = true;
    };

    std::string        buf_;
    std::vector<Frame> stack_;
    bool               afterKey_ = false;

    void sep();
    static void escape(const std::string& in, std::string& out);

    friend std::string quote(const std::string& s);
};

std::string quote(const std::string& s);

}  // namespace rs

#endif  // RS_JSON_H