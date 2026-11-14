// check_timing.cpp — 一.7 时序侧信道
//
// 对「必存在」「必不存在」「疑似被隐藏」三类路径做多轮高精度计时，
// 统计中位数并剔除离群。结论只作为软信号，绝不能单独判定。
#include "rs_checks.h"

#include <algorithm>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

uint64_t timeStatx(const std::string& path, int rounds) {
    std::vector<uint64_t> samples;
    samples.reserve(rounds);

    for (int i = 0; i < rounds; ++i) {
        sys::statx_buf sx{};
        const uint64_t t0 = nowNs();
        (void)sys::statx_(path.c_str(), &sx, nullptr, 0);
        const uint64_t t1 = nowNs();
        samples.push_back(t1 - t0);
    }

    std::sort(samples.begin(), samples.end());
    // 取中位数，天然抗离群
    return samples[samples.size() / 2];
}

}  // namespace

void checkTiming(ScanReport& r) {
    constexpr int kRounds = 64;

    // 必存在：/system/bin/sh
    const uint64_t tExist = timeStatx(RSs("/system/bin/sh"), kRounds);
    // 必不存在：随机路径
    const uint64_t tAbsent = timeStatx(RSs("/system/bin/.__rs_probe_absent__"), kRounds);
    // 疑似被隐藏：/data/adb
    const uint64_t tHidden = timeStatx(RSs("/data/adb"), kRounds);

    Evidence e = makePass(RSs("X.TIMING"), Category::CROSSCHECK,
                          RSs("时序采样完成"));
    e.addVector(RSs("exist_ns"), std::to_string(tExist));
    e.addVector(RSs("absent_ns"), std::to_string(tAbsent));
    e.addVector(RSs("hidden_ns"), std::to_string(tHidden));
    r.add(std::move(e));

    // 若 /data/adb 的耗时明显高于「必不存在」的基线，
    // 说明它在隐藏路径上执行了额外逻辑（而不是直接返回 ENOENT）
    if (tAbsent > 0 && tHidden > tAbsent * 3 && tHidden > 5000) {
        Evidence s = makeHit(RSs("X.TIMING_ANOMALY"), Category::CROSSCHECK,
                             Severity::LOW, Confidence::LOW,
                             RSs("/data/adb 的 stat 耗时异常偏高"));
        s.addVector(RSs("hidden_ns"), std::to_string(tHidden));
        s.addVector(RSs("absent_ns"), std::to_string(tAbsent));
        s.heuristic = true;
        s.fp_note = RSs("软信号：首次访问会触发 page cache 填充，"
                        "必须多轮采样并剔除前 8 次；单独不能作为结论");
        r.add(std::move(s));
    }
}

}  // namespace rs