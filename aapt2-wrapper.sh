#!/bin/sh
exec qemu-x86_64-static -L /usr/x86_64-linux-gnu /root/android-sdk/build-tools/35.0.0/aapt2 "$@"
