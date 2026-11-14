// check_anon_exec.cpp — 匿名可执行映射与代码来源（M6）
//
// 注入框架的共同点：注入的代码必须落在一块「可执行但不对应任何文件」的内存里。
//
// 但 Android 本身也有大量合法匿名可执行映射（ART JIT、vdso、stack）。
// 关键是区分三类：
//   [name]          → 有名字的匿名映射，用途已知，正常
//   [anon:xxx]      → 内核 4.14+ 的命名匿名映射，ART JIT 就用这个
//   空路径          → 真正的无名字匿名映射，正常应该极少
//
// 只有第三类才是强信号。
#include "rs_checks.h"

#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

struct MapEntry {
    uint64_t    start = 0;
    uint64_t    end   = 0;
    std::string perms;
    std::string dev;
    std::string ino;
    std::string path;
};

bool parseMapsLine(const std::string& line, MapEntry& m) {
    const auto sp1 = line.find(' ');
    if (sp1 == std::string::npos) return false;

    const auto range = line.substr(0, sp1);
    const auto dash  = range.find('-');
    if (dash == std::string::npos) return false;

    m.start = std::strtoull(range.substr(0, dash).c_str(), nullptr, 16);
    m.end   = std::strtoull(range.substr(dash + 1).c_str(), nullptr, 16);

    size_t pos = sp1 + 1;
    std::string f[4];
    for (int i = 0; i < 4; ++i) {
        while (pos < line.size() && line[pos] == ' ') ++pos;
        const size_t e = line.find(' ', pos);
        if (e == std::string::npos) {
            f[i] = line.substr(pos);
            pos = line.size();
        } else {
            f[i] = line.substr(pos, e - pos);
            pos = e + 1;
        }
    }
    m.perms = f[0];
    m.dev   = f[2];
    m.ino   = f[3];

    while (pos < line.size() && line[pos] == ' ') ++pos;
    m.path = (pos < line.size()) ? trim(line.substr(pos)) : std::string();
    return true;
}

std::string humanMB(uint64_t bytes) {
    return std::to_string(bytes / (1024 * 1024)) + RSs(" MB");
}

}  // namespace

void checkAnonExec(ScanReport& r) {
    const auto lines = readLines(RSs("/proc/self/maps"), 40000);
    if (lines.empty()) {
        r.add(makeUnknown(RSs("D.TRUE_ANON"), Category::MEMORY, Severity::LOW,
                          RSs("/proc/self/maps 不可读")));
        return;
    }

    int trueAnon = 0;         // dev=00:00 ino=0 path 为空 && 可执行
    int namedAnon = 0;        // 以 [ 开头
    int jitMap = 0;           // memfd:jit-* 或 [anon:dalvik-jit*
    int deletedExec = 0;      // 路径含 (deleted) 且可执行
    uint64_t execBytes = 0;
    uint64_t trueAnonBytes = 0;

    std::vector<std::string> trueAnonList;
    std::vector<std::string> deletedList;

    for (const auto& line : lines) {
        MapEntry m;
        if (!parseMapsLine(line, m)) continue;
        if (m.perms.size() < 3 || m.perms[2] != 'x') continue;

        const uint64_t sz = (m.end > m.start) ? (m.end - m.start) : 0;
        execBytes += sz;

        const bool noFile = (m.dev == RSs("00:00")) && (m.ino == RSs("0"));

        // ART 的 JIT 代码缓存用 memfd 创建后立刻 unlink，
        // 因此它「一定」是 (deleted) —— 这属于正常运行时结构，不是注入。
        const bool isJitPath =
            contains(m.path, RSs("jit")) ||
            contains(m.path, RSs("dalvik")) ||
            contains(m.path, RSs("dalvik-jit"));

        if (contains(m.path, RSs("(deleted)")) && !isJitPath) {
            ++deletedExec;
            if (deletedList.size() < 5) {
                deletedList.push_back(m.path.substr(0, 72));
            }
        }

        if (contains(m.path, RSs("jit")) || contains(m.path, RSs("dalvik-jit"))) {
            ++jitMap;
        }

        if (!m.path.empty() && m.path[0] == '[') {
            ++namedAnon;
            continue;
        }

        if (noFile && m.path.empty()) {
            ++trueAnon;
            trueAnonBytes += sz;
            if (trueAnonList.size() < 6) {
                trueAnonList.push_back(hex64(m.start) + RSs("-") + hex64(m.end) +
                                       RSs(" ") + m.perms + RSs(" ") + humanMB(sz));
            }
        }
    }

    // ---------------------------------------------------------- D.JIT_MAP
    {
        if (jitMap == 0) {
            r.add(makePass(RSs("D.JIT_MAP"), Category::MEMORY,
                           RSs("无 JIT 代码缓存映射")));
        } else {
            Evidence e = makePass(RSs("D.JIT_MAP"), Category::MEMORY,
                                  RSs("JIT 代码缓存映射正常"));
            e.addVector(RSs("count"), std::to_string(jitMap));
            e.detail = RSs("ART JIT 用 memfd / 命名匿名映射，属正常运行时结构");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- D.TRUE_ANON
    // 无名字、无文件、又可执行 —— 这是注入最喜欢落脚的地方。
    // ART 会保留极少量（signal trampoline 等），超过 6 块就要看。
    {
        if (trueAnon == 0) {
            Evidence e = makePass(RSs("D.TRUE_ANON"), Category::MEMORY,
                                  RSs("无无名字的匿名可执行映射"));
            e.addVector(RSs("named_anon"), std::to_string(namedAnon));
            r.add(std::move(e));
        } else if (trueAnon <= 6) {
            Evidence e = makePass(RSs("D.TRUE_ANON"), Category::MEMORY,
                                  RSs("无名字匿名可执行映射数量正常"));
            e.addVector(RSs("count"), std::to_string(trueAnon));
            e.addVector(RSs("bytes"), humanMB(trueAnonBytes));
            for (const auto& s : trueAnonList) e.addVector(RSs("map"), s);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("D.TRUE_ANON"), Category::MEMORY,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("存在大量无名字匿名可执行映射"));
            e.addVector(RSs("count"), std::to_string(trueAnon));
            e.addVector(RSs("bytes"), humanMB(trueAnonBytes));
            for (const auto& s : trueAnonList) e.addVector(RSs("map"), s);
            e.detail = RSs("注入代码通常以 mmap(PROT_EXEC) 落地，"
                           "且不映射任何文件");
            e.fp_note = RSs("某些基带 / 图形驱动会保留较大匿名可执行段，"
                            "需结合 D.MEMFD / D.LIB_MULTI 一起看");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- D.DELETED_EXEC
    {
        if (deletedExec == 0) {
            Evidence e = makePass(RSs("D.DELETED_EXEC"), Category::MEMORY,
                                  RSs("无可执行映射指向已删除文件"));
            e.addVector(RSs("exec_mb"), humanMB(execBytes));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("D.DELETED_EXEC"), Category::MEMORY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("可执行映射指向已删除文件"));
            e.addVector(RSs("count"), std::to_string(deletedExec));
            for (const auto& s : deletedList) e.addVector(RSs("map"), s);
            e.detail = RSs("so 被删除但代码仍在执行 → 动态加载后立刻 unlink，"
                           "是注入框架的典型手法");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- D.EXEC_TOTAL
    {
        Evidence e = makePass(RSs("D.EXEC_TOTAL"), Category::MEMORY,
                              RSs("可执行映射总量已统计"));
        e.addVector(RSs("exec_mb"), humanMB(execBytes));
        e.addVector(RSs("named_anon"), std::to_string(namedAnon));
        e.addVector(RSs("jit"), std::to_string(jitMap));
        e.addVector(RSs("true_anon"), std::to_string(trueAnon));
        r.add(std::move(e));
    }
}

}  // namespace rs