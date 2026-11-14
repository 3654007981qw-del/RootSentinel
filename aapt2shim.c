#include <unistd.h>
#include <stdio.h>
#include <string.h>
#define REAL "/root/android-sdk/build-tools/35.0.0/aapt2"
#define QEMU "/usr/bin/qemu-x86_64-static"
#define LDP  "/usr/x86_64-linux-gnu"
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
