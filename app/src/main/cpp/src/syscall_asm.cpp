// syscall_asm.cpp — 内联汇编 syscall 实现
// 该编译单元在 CMake 中设置 -fno-lto，防止链接期把包装折叠回 libc。
#include "rs_syscall.h"

#include <cerrno>
#include <cstdlib>
#include <ctime>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>

#if defined(__aarch64__)
#define RS_SVC_BODY                                                        \
    register long x8 __asm__("x8") = (n);                                  \
    register long x0 __asm__("x0") = (a);                                  \
    register long x1 __asm__("x1") = (b);                                  \
    register long x2 __asm__("x2") = (c);                                  \
    register long x3 __asm__("x3") = (d);                                  \
    register long x4 __asm__("x4") = (e);                                  \
    register long x5 __asm__("x5") = (f);                                  \
    __asm__ volatile("svc #0"                                              \
                     : "+r"(x0)                                            \
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)\
                     : "memory");                                          \
    return x0;
#elif defined(__x86_64__)
#define RS_SVC_BODY                                                        \
    long ret;                                                              \
    register long r10 __asm__("r10") = (d);                                \
    register long r8  __asm__("r8")  = (e);                                \
    register long r9  __asm__("r9")  = (f);                                \
    __asm__ volatile("syscall"                                             \
                     : "=a"(ret)                                           \
                     : "a"(n), "D"(a), "S"(b), "d"(c),                     \
                       "r"(r10), "r"(r8), "r"(r9)                          \
                     : "rcx", "r11", "memory");                            \
    return ret;
#elif defined(__arm__)
#define RS_SVC_BODY                                                        \
    register long r7 __asm__("r7") = (n);                                  \
    register long r0 __asm__("r0") = (a);                                  \
    register long r1 __asm__("r1") = (b);                                  \
    register long r2 __asm__("r2") = (c);                                  \
    register long r3 __asm__("r3") = (d);                                  \
    register long r4 __asm__("r4") = (e);                                  \
    register long r5 __asm__("r5") = (f);                                  \
    __asm__ volatile("svc #0"                                              \
                     : "+r"(r0)                                            \
                     : "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5), "r"(r7)\
                     : "memory");                                          \
    return r0;
#endif

namespace rs {
namespace sys {

__attribute__((noinline, used))
long raw_a(long n, long a, long b, long c, long d, long e, long f) {
    RS_SVC_BODY
}

__attribute__((noinline, used))
long raw_b(long n, long a, long b, long c, long d, long e, long f) {
    volatile long pad = 0;
    (void)pad;
    RS_SVC_BODY
}

__attribute__((noinline, used))
long raw_c(long n, long a, long b, long c, long d, long e, long f) {
    volatile long pad[3] = {0, 0, 0};
    (void)pad;
    RS_SVC_BODY
}

static inline long pickStub(Stub s, long n, long a, long b, long c,
                            long d, long e, long f) {
    switch (s) {
        case Stub::A: return raw_a(n, a, b, c, d, e, f);
        case Stub::B: return raw_b(n, a, b, c, d, e, f);
        default:      return raw_c(n, a, b, c, d, e, f);
    }
}

long rawWith(Stub s, long n, long a, long b, long c, long d, long e, long f) {
    return pickStub(s, n, a, b, c, d, e, f);
}

long raw(long n, long a, long b, long c, long d, long e, long f) {
    static uintptr_t counter = 0;
    counter = counter * 6364136223846793005ULL + 1442695040888963407ULL;
    // 32 位架构上 counter 只有 32 位，右移 >=32 位是未定义行为
#if defined(__LP64__)
    uint32_t r = (uint32_t)(counter >> 33) ^ (uint32_t)(uintptr_t)&counter;
#else
    uint32_t r = (uint32_t)(counter >> 17) ^ (uint32_t)(uintptr_t)&counter;
#endif
    Stub s = (Stub)(r % 3);
    return pickStub(s, n, a, b, c, d, e, f);
}

static inline long chk(long r) {
    if (r < 0 && r > -4096) {
        errno = (int)(-r);
        return -1;
    }
    errno = 0;
    return r;
}

int openat_(const char* path, int flags, int mode) {
    return (int)chk(raw(NR_openat, (long)AT_FDCWD_, (long)path,
                        (long)flags, (long)mode, 0, 0));
}

int faccessat_(const char* path, int mode, int flags) {
    long n = (flags & AT_EACCESS_) ? NR_faccessat2 : NR_faccessat;
    long r = raw(n, (long)AT_FDCWD_, (long)path, (long)mode, (long)flags, 0, 0);
    if (r == -38L && n == NR_faccessat2) {
        r = raw(NR_faccessat, (long)AT_FDCWD_, (long)path,
                (long)mode, (long)(flags & ~AT_EACCESS_), 0, 0);
    }
    return (int)chk(r);
}

int readlinkat_(const char* path, char* buf, size_t sz) {
    return (int)chk(raw(NR_readlinkat, (long)AT_FDCWD_, (long)path,
                        (long)buf, (long)sz, 0, 0));
}

int getdents64_(int fd, void* buf, size_t sz) {
    return (int)chk(raw(NR_getdents64, (long)fd, (long)buf, (long)sz, 0, 0, 0));
}

int statfs_(const char* path, void* st) {
    return (int)chk(raw(NR_statfs, (long)path, (long)st, 0, 0, 0, 0));
}

ssize_t read_(int fd, void* buf, size_t n) {
    return (ssize_t)chk(raw(NR_read, (long)fd, (long)buf, (long)n, 0, 0, 0));
}

int close_(int fd) {
    return (int)chk(raw(NR_close, (long)fd, 0, 0, 0, 0, 0));
}

uid_t getuid_() {
    return (uid_t)raw(NR_getuid, 0, 0, 0, 0, 0, 0);
}

pid_t getpid_() {
    return (pid_t)raw(NR_getpid, 0, 0, 0, 0, 0, 0);
}

long process_vm_readv_(pid_t pid, const void* local, unsigned long liovcnt,
                       const void* remote, unsigned long riovcnt,
                       unsigned long flags) {
    return raw(NR_process_vm_readv, (long)pid, (long)local, (long)liovcnt,
               (long)remote, (long)riovcnt, (long)flags);
}

int statx_(const char* path, statx_buf* out, bool* usedFallback, int flags) {
    if (usedFallback) *usedFallback = false;

    long r = raw(NR_statx, (long)AT_FDCWD_, (long)path, (long)flags,
                 (long)STATX_BASIC_STATS_, (long)out, 0);

    if (r == 0) return 0;

    if (r == -38L || r == -22L) {
        struct stat st{};
        long r2 = raw(NR_fstatat, (long)AT_FDCWD_, (long)path, (long)&st,
                      (long)flags, 0, 0);
        if (r2 != 0) {
            errno = (int)(-r2);
            return -1;
        }
        if (usedFallback) *usedFallback = true;

        out->stx_ino   = (uint64_t)st.st_ino;
        out->stx_size  = (uint64_t)st.st_size;
        out->stx_mode  = (uint16_t)st.st_mode;
        out->stx_nlink = (uint32_t)st.st_nlink;
        out->stx_uid   = (uint32_t)st.st_uid;
        out->stx_gid   = (uint32_t)st.st_gid;
        out->stx_dev_major = (uint32_t)((uint64_t)st.st_dev >> 32);
        out->stx_dev_minor = (uint32_t)((uint64_t)st.st_dev & 0xffffffff);
        out->stx_mask  = 0;
        return 0;
    }

    errno = (int)(-r);
    return -1;
}

int fstatat_raw(const char* path, rawstat* out, int flags) {
    if (!out) { errno = EINVAL; return -1; }

    statx_buf sx{};
    bool fb = false;
    if (statx_(path, &sx, &fb, flags) == 0) {
        out->dev  = ((uint64_t)sx.stx_dev_major << 32) | sx.stx_dev_minor;
        out->ino  = sx.stx_ino;
        out->size = sx.stx_size;
        return 0;
    }

    struct stat st{};
    long r = raw(NR_fstatat, (long)AT_FDCWD_, (long)path, (long)&st,
                 (long)flags, 0, 0);
    if (r != 0) { errno = (int)(-r); return -1; }
    out->dev  = (uint64_t)st.st_dev;
    out->ino  = (uint64_t)st.st_ino;
    out->size = (uint64_t)st.st_size;
    return 0;
}

SyscallAgreement crossCheckStat(const char* path) {
    SyscallAgreement ag{};
    static const Stub stubs[3] = {Stub::A, Stub::B, Stub::C};

    for (int i = 0; i < 3; ++i) {
        statx_buf sx{};
        long r = rawWith(stubs[i], NR_statx, (long)AT_FDCWD_, (long)path,
                         (long)0, (long)STATX_BASIC_STATS_, (long)&sx, 0);

        long v = (r == 0) ? (long)sx.stx_ino : r;
        ag.values[i] = v;
        ag.tried++;
    }

    ag.all_same = (ag.values[0] == ag.values[1]) &&
                  (ag.values[1] == ag.values[2]);
    if (!ag.all_same) ag.differing = 3;
    return ag;
}

}  // namespace sys
}  // namespace rs