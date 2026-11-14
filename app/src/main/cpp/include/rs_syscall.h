// rs_syscall.h — 直接 syscall 封装
//
// 设计要点：
//  1) 不经过 libc、不经过 JNI、不依赖 PackageManager
//  2) 三份独立实现（raw_a/raw_b/raw_c）位于不同地址，任一被 inline hook 时另两个仍可信
//  3) statx 在旧内核返回 ENOSYS 时降级到 fstatat
//  4) 只用固定布局的 statx_buf，不依赖内核 struct stat（arm64 与 x86_64 布局不同）
#ifndef RS_SYSCALL_H
#define RS_SYSCALL_H

#include <cstddef>
#include <cstdint>
#include <sys/types.h>

namespace rs {
namespace sys {

// ---------------------------------------------------------------- syscall 号
#if defined(__aarch64__)
enum : long {
    NR_read             = 63,
    NR_close            = 57,
    NR_openat           = 56,
    NR_faccessat        = 48,
    NR_faccessat2       = 439,
    NR_readlinkat       = 78,
    NR_fstatat          = 79,
    NR_statx            = 291,
    NR_getdents64       = 61,
    NR_statfs           = 43,
    NR_getuid           = 174,
    NR_getpid           = 172,
    NR_mincore          = 232,
    NR_process_vm_readv = 270,
    NR_bpf              = 280,
    NR_ptrace           = 117,
    NR_getxattr         = 8,
    NR_listxattr        = 6,
    NR_landlock_create_ruleset = 444,
    NR_landlock_add_rule = 445,
    NR_landlock_restrict_self = 446,
    NR_personality      = 92,
    NR_pread64          = 67,
};
#elif defined(__x86_64__)
enum : long {
    NR_read             = 0,
    NR_close            = 3,
    NR_openat           = 257,
    NR_faccessat        = 269,
    NR_faccessat2       = 439,
    NR_readlinkat       = 267,
    NR_fstatat          = 262,
    NR_statx            = 332,
    NR_getdents64       = 217,
    NR_statfs           = 137,
    NR_getuid           = 102,
    NR_getpid           = 39,
    NR_mincore          = 27,
    NR_process_vm_readv = 310,
    NR_bpf              = 321,
    NR_ptrace           = 101,
    NR_getxattr         = 191,
    NR_listxattr        = 192,
    NR_landlock_create_ruleset = 444,
    NR_landlock_add_rule = 445,
    NR_landlock_restrict_self = 446,
    NR_personality      = 135,
    NR_pread64          = 17,
};
#elif defined(__arm__)
enum : long {
    NR_read             = 3,
    NR_close            = 6,
    NR_openat           = 322,
    NR_faccessat        = 334,
    NR_faccessat2       = 439,
    NR_readlinkat       = 332,
    NR_fstatat          = 327,
    NR_statx            = 397,
    NR_getdents64       = 217,
    NR_statfs           = 99,
    NR_getuid           = 199,
    NR_getpid           = 20,
    NR_mincore          = 219,
    NR_process_vm_readv = 376,
    NR_bpf              = 386,
    NR_ptrace           = 26,
    NR_getxattr         = 229,
    NR_listxattr        = 232,
    NR_landlock_create_ruleset = 444,
    NR_landlock_add_rule = 445,
    NR_landlock_restrict_self = 446,
    NR_personality      = 136,
    NR_pread64          = 180,
};
#else
#error "unsupported ABI"
#endif

// ---------------------------------------------------------------- 固定布局 statx
struct statx_ts {
    int64_t  tv_sec;
    uint32_t tv_nsec;
    int32_t  __reserved;
};

struct statx_buf {
    uint32_t stx_mask;
    uint32_t stx_blksize;
    uint64_t stx_attributes;
    uint32_t stx_nlink;
    uint32_t stx_uid;
    uint32_t stx_gid;
    uint16_t stx_mode;
    uint16_t __spare0;
    uint64_t stx_ino;
    uint64_t stx_size;
    uint64_t stx_blocks;
    uint64_t stx_attributes_mask;
    statx_ts stx_atime;
    statx_ts stx_btime;
    statx_ts stx_ctime;
    statx_ts stx_mtime;
    uint32_t stx_rdev_major;
    uint32_t stx_rdev_minor;
    uint32_t stx_dev_major;
    uint32_t stx_dev_minor;
    uint64_t __spare2[14];
};

static_assert(sizeof(statx_buf) == 256, "statx_buf must be 256 bytes");

enum : int {
    AT_FDCWD_            = -100,
    AT_SYMLINK_NOFOLLOW_ = 0x100,
    AT_EACCESS_          = 0x200,
    AT_EMPTY_PATH_       = 0x1000,
    STATX_BASIC_STATS_   = 0x07ff,
};

// ---------------------------------------------------------------- 原始 syscall
long raw_a(long n, long a, long b, long c, long d, long e, long f);
long raw_b(long n, long a, long b, long c, long d, long e, long f);
long raw_c(long n, long a, long b, long c, long d, long e, long f);

long raw(long n, long a = 0, long b = 0, long c = 0,
         long d = 0, long e = 0, long f = 0);

enum class Stub { A = 0, B = 1, C = 2 };
long rawWith(Stub s, long n, long a = 0, long b = 0, long c = 0,
             long d = 0, long e = 0, long f = 0);

// ---------------------------------------------------------------- 便捷封装
int      openat_(const char* path, int flags, int mode = 0);
int      faccessat_(const char* path, int mode, int flags = 0);
int      readlinkat_(const char* path, char* buf, size_t sz);
int      getdents64_(int fd, void* buf, size_t sz);
int      statfs_(const char* path, void* st);
ssize_t  read_(int fd, void* buf, size_t n);
int      close_(int fd);
uid_t    getuid_();
pid_t    getpid_();
long     process_vm_readv_(pid_t pid, const void* local, unsigned long liovcnt,
                           const void* remote, unsigned long riovcnt,
                           unsigned long flags);

int      statx_(const char* path, statx_buf* out, bool* usedFallback = nullptr,
                int flags = 0);

struct rawstat {
    uint64_t dev;
    uint64_t ino;
    uint64_t size;
};
int      fstatat_raw(const char* path, rawstat* out, int flags = 0);

struct SyscallAgreement {
    bool     all_same  = true;
    uint32_t tried     = 0;
    uint32_t differing = 0;
    long     values[3] = {0, 0, 0};
};
SyscallAgreement crossCheckStat(const char* path);

}  // namespace sys
}  // namespace rs

#endif  // RS_SYSCALL_H