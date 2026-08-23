/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.pm;

import android.annotation.NonNull;
import android.content.pm.PackageManagerInternal;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.LocalServices;

/** Keeps KernelSU's UID profile in sync with the effective SUPER_PERMISSION flag. */
final class KernelSuSuperPermissionBridge {
    private static final String TAG = "KernelSuSuperPermission";
    private static PackageList sPackageList;

    private KernelSuSuperPermissionBridge() {}

    static void start() {
        final PackageManagerInternal pm =
                LocalServices.getService(PackageManagerInternal.class);
        final UserManagerInternal um = LocalServices.getService(UserManagerInternal.class);
        if (pm == null || um == null) {
            Slog.w(TAG, "Package or user manager unavailable; root bridge disabled");
            return;
        }

        sPackageList = pm.getPackageList(new PackageManagerInternal.PackageListObserver() {
            @Override
            public void onPackageAdded(@NonNull String packageName, int appId) {
                syncAppId(appId, pm, um, true);
            }

            @Override
            public void onPackageChanged(@NonNull String packageName, int appId) {
                syncAppId(appId, pm, um, true);
            }

            @Override
            public void onPackageRemoved(@NonNull String packageName, int appId) {
                syncAppId(appId, pm, um, true);
            }
        });

        for (String packageName : sPackageList.getPackageNames()) {
            final int uid = pm.getPackageUid(packageName, 0, UserHandle.USER_SYSTEM);
            if (uid >= 0) {
                syncAppId(UserHandle.getAppId(uid), pm, um, false);
            }
        }
    }

    static void syncPackage(@NonNull String packageName, int userId) {
        final PackageManagerInternal pm =
                LocalServices.getService(PackageManagerInternal.class);
        if (pm == null) return;
        final int uid = pm.getPackageUid(packageName, 0, userId);
        if (uid >= 0) {
            syncUid(uid, pm, true);
        }
    }

    private static void syncAppId(int appId, PackageManagerInternal pm, UserManagerInternal um,
            boolean includeDisabled) {
        for (int userId : um.getUserIds()) {
            syncUid(UserHandle.getUid(userId, appId), pm, includeDisabled);
        }
    }

    private static void syncUid(int uid, PackageManagerInternal pm, boolean includeDisabled) {
        final boolean enabled =
                SuperPermissionStore.getInstance().isEnabledOrDeclaredForUid(uid, pm);
        if (!enabled && !includeDisabled) return;
        final int error = nativeSetSuperPermission(uid, enabled);
        if (error != 0 && error != -1 && error != -19 && error != -25) {
            // EPERM/ENODEV/ENOTTY mean the running kernel has no compatible bridge.
            Slog.w(TAG, "KernelSU profile sync failed for uid " + uid + ": " + error);
        }
    }

    private static native int nativeSetSuperPermission(int uid, boolean enabled);
}
