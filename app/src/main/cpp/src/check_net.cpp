// check_net.cpp — 网络 / 端口 / socket 层（M5）
//
// 这层抓的是「以进程外形态存在的注入器」：
//   · Frida server 默认监听 27042，可改但常批量开
//   · /proc/net/unix 里的抽象 socket 常被用于跨进程通道
//   · 本地监听端口数量异常 → 可能跑着调试服务
#include "rs_checks.h"

#include <cstdlib>
#include <map>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

struct ListenPort {
    int         port = 0;
    std::string addr;
    std::string proto;
    uint64_t    inode = 0;   // /proc/net/tcp* 的 socket inode（归属判定用）
};

// /proc/net/tcp 里 local_address 形如 "0100007F:69A2"（小端 IP + 大端端口）
int hexPort(const std::string& s) {
    const auto pos = s.find(':');
    if (pos == std::string::npos) return -1;
    return static_cast<int>(std::strtol(s.c_str() + pos + 1, nullptr, 16));
}

std::vector<ListenPort> collectListeners(const std::string& path,
                                         const std::string& proto) {
    std::vector<ListenPort> out;
    const auto data = readFile(path, 512 * 1024);
    if (data.empty()) return out;

    const auto lines = split(data, '\n');
    for (size_t i = 0; i < lines.size(); ++i) {
        if (i == 0) continue;  // 表头
        const auto cols = split(lines[i], ' ');
        std::vector<std::string> f;
        for (const auto& c : cols) {
            if (!c.empty()) f.push_back(c);
        }
        if (f.size() < 4) continue;
        // f[1]=local_address  f[3]=st  f[9]=inode
        if (f[3] != RSs("0A")) continue;  // TCP_LISTEN
        const int p = hexPort(f[1]);
        uint64_t ino = 0;
        if (f.size() > 9) ino = std::strtoull(f[9].c_str(), nullptr, 10);
        if (p > 0) out.push_back(ListenPort{p, f[1], proto, ino});
    }
    return out;
}

}  // namespace

void checkNet(ScanReport& r) {
    auto tcp4 = collectListeners(RSs("/proc/net/tcp"), RSs("tcp4"));
    auto tcp6 = collectListeners(RSs("/proc/net/tcp6"), RSs("tcp6"));

    auto all = tcp4;
    all.insert(all.end(), tcp6.begin(), tcp6.end());

    // ---------------------------------------------------------- X.FRIDA_PORT
    {
        static const int fridaPorts[] = {27042, 27043, 27044, 27045, 27046};
        // v2.22.45（审查 A P0-5）：/proc/net/tcp* 是网络命名空间级视图，
        // 端口不一定是本进程开的。先收集本进程 fd 持有的 socket inode，
        // 只有监听 socket 的 inode 属于本进程才按 CRITICAL 判；仅在
        // 命名空间里存在（别的进程开的）降为 MEDIUM + heuristic。
        std::vector<uint64_t> ownInodes;
        for (const auto& fe : listDirRaw(RSs("/proc/self/fd"))) {
            const std::string t = readLink(RSs("/proc/self/fd/") + fe.name);
            if (startsWith(t, RSs("socket:[")) && t.size() > 9) {
                ownInodes.push_back(
                    std::strtoull(t.c_str() + 8, nullptr, 10));
            }
        }
        std::vector<std::string> hit;
        std::vector<std::string> foreignHit;
        for (const auto& lp : all) {
            for (const int p : fridaPorts) {
                if (lp.port == p) {
                    bool own = false;
                    for (const uint64_t oi : ownInodes) {
                        if (oi == lp.inode && lp.inode != 0) { own = true; break; }
                    }
                    (own ? hit : foreignHit).push_back(
                        lp.proto + RSs(":") + std::to_string(lp.port));
                    break;
                }
            }
        }

        if (!hit.empty()) {
            Evidence e = makeHit(RSs("X.FRIDA_PORT"), Category::CROSSCHECK,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("本进程监听命中 Frida 默认端口"));
            for (const auto& h : hit) e.addVector(RSs("listen"), h);
            e.detail = RSs("监听 socket 的 inode 经 /proc/self/fd 对照，确属本进程");
            r.add(std::move(e));
        } else if (!foreignHit.empty()) {
            Evidence e = makeHit(RSs("X.FRIDA_PORT"), Category::CROSSCHECK,
                                 Severity::MEDIUM, Confidence::LOW,
                                 RSs("网络命名空间内存在 Frida 默认端口监听（非本进程）"));
            for (const auto& h : foreignHit) e.addVector(RSs("listen"), h);
            e.heuristic = true;
            e.detail = RSs("frida-server 默认监听 27042，可用 -l 改端口");
            e.fp_note = RSs("该端口属于同一网络命名空间内的其他进程，"
                            "不是本进程被注入的直接证据");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("X.FRIDA_PORT"), Category::CROSSCHECK,
                                  RSs("未监听 Frida 默认端口"));
            e.addVector(RSs("total_listen"), std::to_string(all.size()));
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- X.TCP_LISTEN
    {
        if (all.empty()) {
            r.add(makePass(RSs("X.TCP_LISTEN"), Category::CROSSCHECK,
                           RSs("本机网络命名空间无本地监听端口")));
        } else {
            std::vector<std::string> list;
            for (const auto& lp : all) {
                if (list.size() >= 8) break;
                list.push_back(lp.proto + RSs(":") + std::to_string(lp.port));
            }
            // v2.22.43 文案修正：/proc/net/tcp* 是网络命名空间级视图，
            // 端口不一定是本进程开的。旧标题「本进程持有」与本项自己的
            // fp_note 自相矛盾，现与 fp_note 口径一致。
            Evidence e = makeHit(RSs("X.TCP_LISTEN"), Category::CROSSCHECK,
                                 Severity::LOW, Confidence::LOW,
                                 RSs("本机网络命名空间存在本地监听端口"));
            e.addVector(RSs("count"), std::to_string(all.size()));
            for (const auto& s : list) e.addVector(RSs("listen"), s);
            e.fp_note = RSs("这些端口属于整个网络命名空间，不一定是本进程开的");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- D.UNIX_ABSTRA
    {
        const auto unixData = readFile(RSs("/proc/net/unix"), 512 * 1024);
        if (unixData.empty()) {
            r.add(makeUnknown(RSs("D.UNIX_ABSTRA"), Category::MEMORY, Severity::LOW,
                              RSs("/proc/net/unix 不可读")));
        } else {
            static const std::vector<std::string> bad = {
                RSs("frida"), RSs("gum-js"), RSs("re.frida"), RSs("linjector"),
            };
            std::vector<std::string> found;
            int total = 0;

            for (const auto& line : split(unixData, '\n')) {
                if (line.empty() || line[0] == 'N') continue;
                ++total;
                const auto low = toLower(line);
                for (const auto& k : bad) {
                    if (contains(low, k)) {
                        found.push_back(trim(line));
                        break;
                    }
                }
            }

            if (!found.empty()) {
                Evidence e = makeHit(RSs("D.UNIX_ABSTRA"), Category::MEMORY,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("unix socket 路径命中注入框架"));
                for (const auto& f : found) e.addVector(RSs("socket"), f);
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("D.UNIX_ABSTRA"), Category::MEMORY,
                                      RSs("unix socket 无注入特征"));
                e.addVector(RSs("count"), std::to_string(total));
                r.add(std::move(e));
            }
        }
    }
}

}  // namespace rs