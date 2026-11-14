#include <unistd.h>
#include <stdio.h>
#include <string.h>
// 路径不在源码里写死（v2.22.44，P1-04）：下面是占位符，
// setup-aapt2.sh 生成 shim 时按实际 SDK / build-tools 替换。
// 直接编译本文件（不经脚本）会得到占位路径，exec 必然失败 ——
// 请始终通过 setup-aapt2.sh 生成。
#define REAL "__REAL_AAPT2__"
#define QEMU "__QEMU__"
#define LDP  "__LDP__"
int main(int argc, char** argv) {
    if (argc >= 2 && (strcmp(argv[1],"version")==0 || strcmp(argv[1],"--version")==0)) {
        printf("Android Asset Packaging Tool (aapt) 2.19-11948202\\n"); fflush(stdout); return 0;
    }
    char* a[argc + 4]; int n = 0;
    a[n++] = (char*)QEMU; a[n++] = (char*)"-L"; a[n++] = (char*)LDP;
    a[n++] = (char*)REAL;
    for (int i = 1; i < argc; i++) a[n++] = argv[i];
    a[n] = NULL;
    execv(QEMU, a); perror("execv"); return 127;
}
