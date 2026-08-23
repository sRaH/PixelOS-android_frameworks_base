/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <nativehelper/JNIHelp.h>

namespace android {
namespace {

constexpr unsigned int kInstallMagic1 = 0xDEADBEEF;
constexpr unsigned int kInstallMagic2 = 0xCAFEBABE;
constexpr unsigned int kSetSuperPermission = _IOC(_IOC_WRITE, 'K', 20, 0);

struct SetSuperPermissionCommand {
    uint32_t uid;
    uint8_t enabled;
};

jint nativeSetSuperPermission(JNIEnv*, jclass, jint uid, jboolean enabled) {
    int fd = -1;
    errno = 0;
    syscall(SYS_reboot, kInstallMagic1, kInstallMagic2, 0, &fd);
    if (fd < 0) {
        return errno != 0 ? -errno : -ENODEV;
    }

    SetSuperPermissionCommand command = {
            .uid = static_cast<uint32_t>(uid),
            .enabled = static_cast<uint8_t>(enabled),
    };
    const int result = ioctl(fd, kSetSuperPermission, &command);
    const int error = result == 0 ? 0 : -errno;
    close(fd);
    return error;
}

const JNINativeMethod gMethods[] = {
        {"nativeSetSuperPermission", "(IZ)I", (void*)nativeSetSuperPermission},
};

} // namespace

int register_android_server_pm_KernelSuSuperPermissionBridge(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/server/pm/KernelSuSuperPermissionBridge", gMethods,
            sizeof(gMethods) / sizeof(gMethods[0]));
}

} // namespace android
