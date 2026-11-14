// util.cpp — 工具函数实现
#include "rs_util.h"

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "rs_syscall.h"

namespace rs {

// ---------------------------------------------------------------- 三视角读文件
//
// v2.4.1 修复：
//   1. maxBytes 只是「检查点」而不是上限：先 append 8 KB 再判断，
//      实际最多可超出上限 8191 字节。现在每轮按剩余额度算读取长度。
//   2. maxBytes == 0 会被当成「读完立刻停」→ 几乎总返回空串。
//      现在 0 视为「用默认上限」。
//   3. ok_* 只表示 open 成功，read 失败也照样 true。现在只有
//      open 成功且 read 没出错（EOF 允许）才算 ok。
//   4. 只有一个视角能读时 consistent 保持默认 true，调用方会当成
//      「三视角一致」。现在额外给出 crossVerified / readableViews。
//   5. /proc、/sys、/dev 下的节点每次读内容都可能变化，
//      跨视角比较内容必然「不一致」→ 误报。动态节点只比可见性。

static bool isDynamicPath(const std::string& p) {
    if (p.rfind("/proc/", 0) == 0) return true;
    if (p.rfind("/sys/", 0) == 0) return true;
    if (p.rfind("/dev/", 0) == 0) return true;
    return false;
}

static std::string readRawImpl(const std::string& path, size_t maxBytes, bool* ok) {
    *ok = false;
    int fd = sys::openat_(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};

    std::string out;
    out.reserve(maxBytes < 65536 ? maxBytes : 65536);
    char buf[8192];
    bool readErr = false;

    while (out.size() < maxBytes) {
        size_t left = maxBytes - out.size();
        size_t want = (left < sizeof(buf)) ? left : sizeof(buf);
        ssize_t n = sys::read_(fd, buf, want);
        if (n < 0) {
            readErr = true;
            break;
        }
        if (n == 0) break;              // EOF
        out.append(buf, (size_t)n);
    }
    sys::close_(fd);
    *ok = !readErr;                     // 空文件也算成功读取
    return out;
}

static std::string readLibcImpl(const std::string& path, size_t maxBytes, bool* ok) {
    *ok = false;
    FILE* f = std::fopen(path.c_str(), "re");
    if (!f) return {};

    std::string out;
    char buf[8192];
    bool err = false;

    while (out.size() < maxBytes) {
        size_t left = maxBytes - out.size();
        size_t want = (left < sizeof(buf)) ? left : sizeof(buf);
        size_t n = std::fread(buf, 1, want, f);
        if (n == 0) {
            if (std::ferror(f)) err = true;
            break;
        }
        out.append(buf, n);
    }
    std::fclose(f);
    *ok = !err;
    return out;
}

static std::string readStreamImpl(const std::string& path, size_t maxBytes, bool* ok) {
    *ok = false;
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) return {};

    std::string out;
    char buf[8192];
    while (out.size() < maxBytes) {
        size_t left = maxBytes - out.size();
        size_t want = (left < sizeof(buf)) ? left : sizeof(buf);
        ifs.read(buf, (std::streamsize)want);
        std::streamsize got = ifs.gcount();
        if (got <= 0) break;
        out.append(buf, (size_t)got);
    }
    *ok = !ifs.bad();
    return out;
}

Read3 readFile3(const std::string& path, size_t maxBytes) {
    Read3 r;
    // maxBytes == 0 视为「使用默认上限」，而不是「读 0 字节」
    const size_t cap = (maxBytes == 0) ? (1u << 20) : maxBytes;

    bool ok = false;
    r.data_raw = readRawImpl(path, cap, &ok);
    r.ok_raw = ok;

    ok = false;
    r.data_libc = readLibcImpl(path, cap, &ok);
    r.ok_libc = ok;

    ok = false;
    r.data_stream = readStreamImpl(path, cap, &ok);
    r.ok_stream = ok;

    r.readableViews = (r.ok_raw ? 1 : 0) + (r.ok_libc ? 1 : 0) + (r.ok_stream ? 1 : 0);

    if (r.readableViews >= 2) {
        r.crossVerified = true;

        if (isDynamicPath(path)) {
            // /proc、/sys、/dev：每次读都可能不一样，只确认可见性
            r.note = "动态节点，仅比对可见性";
        } else {
            const std::string& a = r.ok_raw ? r.data_raw
                                 : (r.ok_libc ? r.data_libc : r.data_stream);
            bool diff = false;
            if (r.ok_raw && r.data_raw != a) diff = true;
            if (r.ok_libc && r.data_libc != a) diff = true;
            if (r.ok_stream && r.data_stream != a) diff = true;
            if (diff) {
                r.consistent = false;
                r.note = "多视角读取内容不一致";
            }
        }
    } else {
        r.crossVerified = false;
        if (r.readableViews == 0) {
            r.note = "所有视角都读不到（SELinux 拒绝或路径不存在）";
        } else {
            r.note = "仅 1 个视角可读，无法交叉验证";
        }
    }

    // 可见性差异：openat 能读而 fopen 读不到（或反之）本身是可疑的。
    // 注意这是「结论未知」而不是「不一致」，note 里已经写明视角数量。
    if (r.readableViews > 0 && r.readableViews < 3 && r.ok_raw != r.ok_libc) {
        r.consistent = false;
        r.note = "raw 与 libc 的可见性不一致";
    }

    return r;
}

std::string readFile(const std::string& path, size_t maxBytes) {
    // v2.4.1：readRawImpl 现在多一个 out 参数，用于区分「读失败」与「读空」
    bool ok = false;
    const size_t cap = (maxBytes == 0) ? (1u << 20) : maxBytes;
    return readRawImpl(path, cap, &ok);
}

std::vector<std::string> readLines(const std::string& path, size_t maxLines) {
    bool ok = false;
    std::string data = readRawImpl(path, 1u << 20, &ok);
    std::vector<std::string> out;
    if (data.empty()) return out;

    size_t start = 0;
    for (size_t i = 0; i < data.size() && out.size() < maxLines; ++i) {
        if (data[i] == '\n') {
            out.push_back(data.substr(start, i - start));
            start = i + 1;
        }
    }
    if (start < data.size() && out.size() < maxLines) {
        out.push_back(data.substr(start));
    }
    return out;
}

// ---------------------------------------------------------------- 存在性
bool fileExistsRaw(const std::string& path) {
    sys::statx_buf sx{};
    return sys::statx_(path.c_str(), &sx, nullptr, 0) == 0;
}

// v2.22.45（审查 A P1-13）：三态探测。fileExistsRaw 把 EACCES 与
// ENOENT 压成同一个 false，目录枚举互验会把「权限读不到」误判成
// 「枚举到但不存在」。返回值：0=存在 1=ENOENT（确不存在） 2=其他
// 错误（不可验）。statx_ 在失败路径上会设置 errno。
int fileStateRaw(const std::string& path) {
    sys::statx_buf sx{};
    if (sys::statx_(path.c_str(), &sx, nullptr, 0) == 0) return 0;
    return errno == ENOENT ? 1 : 2;
}

int accessState(const std::string& path, int mode) {
    if (sys::faccessat_(path.c_str(), mode, 0) == 0) return 0;

    int e = errno;
    if (e == EACCES || e == EPERM) return 1;
    return 2;
}

int accessErrnoRaw(const std::string& path, int mode) {
    if (sys::faccessat_(path.c_str(), mode, 0) == 0) return 0;
    return errno;
}

// ---------------------------------------------------------------- 链接
std::string readLink(const std::string& path) {
    char buf[4096];
    int n = sys::readlinkat_(path.c_str(), buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    return std::string(buf, (size_t)n);
}

// ---------------------------------------------------------------- 目录
struct LinuxDirent64 {
    uint64_t       d_ino;
    int64_t        d_off;
    unsigned short d_reclen;
    unsigned char  d_type;
    char           d_name[];
};

std::vector<DirEntry> listDirRaw(const std::string& path) {
    std::vector<DirEntry> out;
    int fd = sys::openat_(path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return out;

    size_t bufsz = pageSize() * 4;
    std::vector<char> buf(bufsz);

    for (;;) {
        int n = sys::getdents64_(fd, buf.data(), bufsz);
        if (n <= 0) break;

        long off = 0;
        while (off < n) {
            auto* d = (LinuxDirent64*)(buf.data() + off);
            if (d->d_reclen == 0) break;
            std::string name(d->d_name);
            if (name != "." && name != "..") {
                out.push_back(DirEntry{name, d->d_type});
            }
            off += d->d_reclen;
        }
    }
    sys::close_(fd);
    return out;
}

std::vector<std::string> listDirLibc(const std::string& path) {
    std::vector<std::string> out;
    DIR* d = opendir(path.c_str());
    if (!d) return out;
    while (struct dirent* e = readdir(d)) {
        std::string n(e->d_name);
        if (n != "." && n != "..") out.push_back(n);
    }
    closedir(d);
    return out;
}

bool dirConsistent(const std::string& path, std::string* note) {
    auto a = listDirRaw(path);
    auto b = listDirLibc(path);

    if (a.size() == b.size()) return true;

    if (note) {
        *note = "getdents64=" + std::to_string(a.size()) +
                " readdir=" + std::to_string(b.size());
    }
    return false;
}

// ---------------------------------------------------------------- 属性
std::string getProp(const std::string& name) {
    char value[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(name.c_str(), value);
    if (n <= 0) return {};
    return std::string(value, (size_t)n);
}

// ---------------------------------------------------------------- 系统
size_t pageSize() {
    static size_t ps = 0;
    if (ps == 0) {
        long v = sysconf(_SC_PAGESIZE);
        ps = (v > 0) ? (size_t)v : 4096;
    }
    return ps;
}

uint64_t nowMs() {
    using namespace std::chrono;
    return (uint64_t)duration_cast<milliseconds>(
        steady_clock::now().time_since_epoch()).count();
}

uint64_t nowNs() {
    using namespace std::chrono;
    return (uint64_t)duration_cast<nanoseconds>(
        steady_clock::now().time_since_epoch()).count();
}

std::string errnoName(int e) {
    switch (e) {
        case 0:       return "OK";
        case EACCES:  return "EACCES";
        case EPERM:   return "EPERM";
        case ENOENT:  return "ENOENT";
        case ENOTDIR: return "ENOTDIR";
        case EISDIR:  return "EISDIR";
        case EBADF:   return "EBADF";
        case ENOSYS:  return "ENOSYS";
        case EINVAL:  return "EINVAL";
        default:      return "E" + std::to_string(e);
    }
}

// ---------------------------------------------------------------- 字符串
bool startsWith(const std::string& s, const std::string& p) {
    return s.size() >= p.size() && s.compare(0, p.size(), p) == 0;
}

bool endsWith(const std::string& s, const std::string& p) {
    return s.size() >= p.size() &&
           s.compare(s.size() - p.size(), p.size(), p) == 0;
}

bool contains(const std::string& s, const std::string& p) {
    return s.find(p) != std::string::npos;
}

bool containsAny(const std::string& s, const std::vector<std::string>& keys) {
    for (const auto& k : keys) {
        if (!k.empty() && contains(s, k)) return true;
    }
    return false;
}

std::string toLower(const std::string& s) {
    std::string o = s;
    for (char& c : o) {
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    }
    return o;
}

std::string trim(const std::string& s) {
    size_t b = 0, e = s.size();
    while (b < e && (s[b] == ' ' || s[b] == '\t' || s[b] == '\r' || s[b] == '\n')) ++b;
    while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' ||
                     s[e - 1] == '\r' || s[e - 1] == '\n')) --e;
    return s.substr(b, e - b);
}

std::vector<std::string> split(const std::string& s, char sep) {
    std::vector<std::string> out;
    size_t start = 0;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == sep) {
            out.push_back(s.substr(start, i - start));
            start = i + 1;
        }
    }
    out.push_back(s.substr(start));
    return out;
}

std::string hex64(uint64_t v) {
    char tmp[24];
    std::snprintf(tmp, sizeof(tmp), "0x%016llx", (unsigned long long)v);
    return tmp;
}

std::string join(const std::vector<std::string>& v, const std::string& sep) {
    std::string out;
    for (size_t i = 0; i < v.size(); ++i) {
        if (i) out += sep;
        out += v[i];
    }
    return out;
}

// ---------------------------------------------------------------- 证据辅助
Evidence makeHit(const std::string& id, Category cat, Severity sev,
                 Confidence conf, const std::string& title,
                 const std::string& detail) {
    return Evidence::make(id, cat, sev, conf, Verdict::HIT, title, detail);
}

Evidence makePass(const std::string& id, Category cat,
                  const std::string& title) {
    return Evidence::make(id, cat, Severity::INFO, Confidence::HIGH,
                          Verdict::PASS, title);
}

Evidence makeUnknown(const std::string& id, Category cat, Severity sev,
                     const std::string& title, const std::string& detail) {
    return Evidence::make(id, cat, sev, Confidence::LOW,
                          Verdict::UNKNOWN, title, detail);
}

void addCrossCheck(ScanReport& report, const CrossCheck& cc) {
    report.addCross(cc);

    std::string detail;
    for (const auto& kv : cc.observations) {
        if (!detail.empty()) detail += "; ";
        detail += kv.first + "=" + kv.second;
    }

    if (cc.consistent) {
        Evidence e = makePass("X." + cc.id, Category::CROSSCHECK,
                              "多视角一致: " + cc.subject);
        e.vectors.push_back(detail);
        report.add(std::move(e));
    } else {
        Evidence e = makeHit("X." + cc.id, Category::CROSSCHECK,
                             Severity::HIGH, Confidence::HIGH,
                             "交叉验证矛盾: " + cc.subject,
                             detail);
        e.heuristic = false;
        report.add(std::move(e));
    }
}

}  // namespace rs