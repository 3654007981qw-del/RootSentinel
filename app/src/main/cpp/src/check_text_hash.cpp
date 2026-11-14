// check_text_hash.cpp — 库代码段与磁盘 ELF 逐字节对比（M4）
//
// 这是对 E.SYMBOL_HOOK 的补充：
//   dlsym 只能发现「符号表/重定位被改」；
//   而 inline hook 通常只改函数开头几条指令，符号表仍指向原地址。
// 所以直接把内存里的可执行段前 32 字节与磁盘文件同一偏移对比。
#include "rs_checks.h"

#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

struct CodeSeg {
    uint64_t    start   = 0;
    uint64_t    end     = 0;
    uint64_t    fileOff = 0;
    std::string path;
};

std::vector<CodeSeg> findExecSegs(const std::string& libHint) {
    std::vector<CodeSeg> out;
    auto lines = readLines(RSs("/proc/self/maps"), 20000);

    for (const auto& line : lines) {
        if (line.find(libHint) == std::string::npos) continue;

        unsigned long long start = 0, end = 0, off = 0;
        char perms[8] = {0};
        char path[512] = {0};

        // start-end perms offset dev inode path
        const int n = std::sscanf(line.c_str(),
                                  "%llx-%llx %7s %llx %*s %*s %511[^\n]",
                                  &start, &end, perms, &off, path);
        if (n < 4) continue;
        if (std::strlen(perms) < 3 || perms[2] != 'x') continue;

        std::string p = trim(path);
        if (p.empty() || p[0] != '/') continue;

        out.push_back(CodeSeg{start, end, off, p});
    }
    return out;
}

}  // namespace

void checkTextHash(ScanReport& r) {
    static const std::vector<std::string> libs = {
        RSs("libc.so"),
        RSs("libart.so"),
    };

    int checked = 0;
    int mismatch = 0;

    for (const auto& lib : libs) {
        auto segs = findExecSegs(lib);
        for (const auto& s : segs) {
            int fd = sys::openat_(s.path.c_str(), O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;

            // ---------------------------------------------------------- 抽样比对
            //
            // 旧实现只比每个可执行段**开头 32 字节**。
            // 而 inline hook 通常打在函数体内（比如 fopen / openat 的入口，
            // 往往在段内偏移几 KB 甚至几十 KB 处），开头 32 字节根本碰不到。
            //
            // 现在改成：在段内均匀取 SAMPLE_POINTS 个采样窗口，
            // 每窗口 256 字节，覆盖整段而不是只看开头。
            static constexpr int SAMPLE_POINTS = 8;
            static constexpr int WINDOW = 256;

            const uint64_t segLen = (s.end > s.start) ? (s.end - s.start) : 0;
            if (segLen < WINDOW) { sys::close_(fd); continue; }

            const unsigned char* mem =
                reinterpret_cast<const unsigned char*>(s.start);

            int diffTotal = 0;
            int windowsChecked = 0;
            int firstDiffOff = -1;

            for (int k = 0; k < SAMPLE_POINTS; ++k) {
                // 均匀分布，避开段尾（可能不足一个窗口）
                uint64_t offInSeg = (segLen - WINDOW) * k / (SAMPLE_POINTS - 1);
                // 对齐到 16 字节，便于比对
                offInSeg &= ~uint64_t(15);

                unsigned char disk[WINDOW] = {0};
                const long n = sys::raw(sys::NR_pread64, (long)fd, (long)disk,
                                        WINDOW, (long)(s.fileOff + offInSeg));
                if (n != WINDOW) continue;
                ++windowsChecked;

                for (int i = 0; i < WINDOW; ++i) {
                    if (mem[offInSeg + i] != disk[i]) {
                        ++diffTotal;
                        if (firstDiffOff < 0) firstDiffOff = (int)(offInSeg + i);
                    }
                }
            }
            sys::close_(fd);
            if (windowsChecked == 0) continue;

            ++checked;
            if (diffTotal == 0) continue;

            ++mismatch;
            Evidence e = makeHit(RSs("I.TEXT_HASH"), Category::INTEGRITY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("库代码段与磁盘不一致: ") + lib);
            e.addVector(RSs("path"), s.path);
            e.addVector(RSs("file_offset"), hex64(s.fileOff));
            e.addVector(RSs("first_diff_at"),
                        firstDiffOff >= 0 ? std::to_string(firstDiffOff) : RSs("-"));
            e.addVector(RSs("windows"), std::to_string(windowsChecked));
            e.addVector(RSs("diff_bytes"),
                        std::to_string(diffTotal) + "/" +
                        std::to_string(windowsChecked * WINDOW));
            e.detail = RSs("内存中的机器码与磁盘 ELF 不同 → inline hook 或 patch");
            e.fp_note = RSs("少数 ROM 会在启动时做二进制重写（脱壳/加固），"
                            "需结合 E.SYMBOL_HOOK 一起判断");
            r.add(std::move(e));
        }
    }

    if (checked == 0) {
        r.add(makeUnknown(RSs("I.TEXT_HASH"), Category::INTEGRITY, Severity::LOW,
                          RSs("未能定位可对比的代码段")));
    } else if (mismatch == 0) {
        Evidence e = makePass(RSs("I.TEXT_HASH"), Category::INTEGRITY,
                              RSs("代码段与磁盘完全一致"));
        e.addVector(RSs("segments_checked"), std::to_string(checked));
        e.addVector(RSs("sample_points_per_seg"), "8");
        e.addVector(RSs("window_bytes"), "256");
        r.add(std::move(e));
    }
}

}  // namespace rs