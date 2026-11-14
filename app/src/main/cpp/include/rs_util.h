// rs_util.h — 通用工具：多视角读文件、目录、属性、字符串
#ifndef RS_UTIL_H
#define RS_UTIL_H

#include <cstdint>
#include <string>
#include <vector>

#include "rs_types.h"

namespace rs {

// ---------------------------------------------------------------- 三视角读文件
struct Read3 {
    bool        ok_raw     = false;
    bool        ok_libc    = false;
    bool        ok_stream  = false;
    std::string data_raw;
    std::string data_libc;
    std::string data_stream;
    bool        consistent = true;
    std::string note;

    // ---- v2.4.1 新增 ----
    /** 成功读到内容的视角数量（0~3） */
    int  readableViews = 0;
    /**
     * 是否真的完成了交叉验证（≥2 个视角都读到了内容）。
     *
     * 之前只有 consistent 一个布尔：单个视角能读时 consistent 保持默认 true，
     * 调用方就会把它当成「三个视角一致」——那只是「没比过」。
     * 需要「确认一致」语义的地方应该同时检查 crossVerified。
     */
    bool crossVerified = false;

    bool anyOk() const { return ok_raw || ok_libc || ok_stream; }
    const std::string& best() const {
        if (ok_raw) return data_raw;
        if (ok_libc) return data_libc;
        return data_stream;
    }
};

Read3 readFile3(const std::string& path, size_t maxBytes = (1u << 20));

std::string readFile(const std::string& path, size_t maxBytes = (1u << 20));
std::vector<std::string> readLines(const std::string& path, size_t maxLines = 20000);

// ---------------------------------------------------------------- 存在性
bool fileExistsRaw(const std::string& path);
// 0 = 可访问；1 = 存在但拒绝；2 = 不存在
int  accessState(const std::string& path, int mode = 4);
// v2.22.41：带 errno 的访问探测。成功返回 0，失败返回原始 errno。
// accessState 把失败压成 1/2 两档，调用方分不清 ENOENT（真不存在）
// 与 EIO/ENOSYS（状态不明）——需要精确区分时用这个。
int  accessErrnoRaw(const std::string& path, int mode = 4);

// ---------------------------------------------------------------- 链接
std::string readLink(const std::string& path);

// ---------------------------------------------------------------- 目录
struct DirEntry {
    std::string name;
    uint8_t     type = 0;
};

std::vector<DirEntry>    listDirRaw(const std::string& path);
std::vector<std::string> listDirLibc(const std::string& path);
bool                     dirConsistent(const std::string& path,
                                       std::string* note = nullptr);

// ---------------------------------------------------------------- 属性
std::string getProp(const std::string& name);

// ---------------------------------------------------------------- 系统
size_t      pageSize();
uint64_t    nowMs();
uint64_t    nowNs();
std::string errnoName(int e);

// ---------------------------------------------------------------- 字符串
bool startsWith(const std::string& s, const std::string& p);
bool endsWith(const std::string& s, const std::string& p);
bool contains(const std::string& s, const std::string& p);
bool containsAny(const std::string& s, const std::vector<std::string>& keys);
std::string toLower(const std::string& s);
std::string trim(const std::string& s);
std::vector<std::string> split(const std::string& s, char sep);
std::string hex64(uint64_t v);
std::string join(const std::vector<std::string>& v, const std::string& sep);

// ---------------------------------------------------------------- 证据辅助
Evidence makeHit(const std::string& id, Category cat, Severity sev,
                 Confidence conf, const std::string& title,
                 const std::string& detail = "");

Evidence makePass(const std::string& id, Category cat,
                  const std::string& title);

Evidence makeUnknown(const std::string& id, Category cat, Severity sev,
                     const std::string& title, const std::string& detail = "");

void addCrossCheck(ScanReport& report, const CrossCheck& cc);

}  // namespace rs

#endif  // RS_UTIL_H