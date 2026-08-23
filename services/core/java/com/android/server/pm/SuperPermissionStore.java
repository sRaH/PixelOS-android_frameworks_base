/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.content.pm.PackageManagerInternal;
import android.os.Environment;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.pm.pkg.AndroidPackage;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Persistent system-server state for the per-package, per-user super-permission flag. */
public final class SuperPermissionStore {
    private static final String TAG = "SuperPermissionStore";
    private static final String FILE_NAME = "super-permissions.xml";
    private static final String TAG_ROOT = "super-permissions";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_ENABLED = "enabled";

    private static final SuperPermissionStore sInstance = new SuperPermissionStore(
            new File(Environment.getDataSystemDirectory(), FILE_NAME));

    private final Object mLock = new Object();
    private final AtomicFile mFile;
    private final SparseArray<ArrayMap<String, Boolean>> mPackagesByUser = new SparseArray<>();

    public static @NonNull SuperPermissionStore getInstance() {
        return sInstance;
    }

    SuperPermissionStore(@NonNull File file) {
        mFile = new AtomicFile(file, TAG);
        synchronized (mLock) {
            readLocked();
        }
    }

    public boolean isEnabled(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            final ArrayMap<String, Boolean> packages = mPackagesByUser.get(userId);
            return packages != null && Boolean.TRUE.equals(packages.get(packageName));
        }
    }

    /** Returns whether the package is enabled by stored state or its manifest declaration. */
    public boolean isEnabledOrDeclared(@NonNull String packageName, int userId,
            @NonNull PackageManagerInternal packageManager) {
        synchronized (mLock) {
            final ArrayMap<String, Boolean> packages = mPackagesByUser.get(userId);
            if (packages != null && packages.containsKey(packageName)) {
                return packages.get(packageName);
            }
        }
        final AndroidPackage pkg = packageManager.getPackage(packageName);
        return pkg != null
                && packageManager.getPackageUid(packageName, 0, userId) >= 0
                && pkg.getRequestedPermissions().contains(Manifest.permission.SUPER_PERMISSION);
    }

    public boolean isEnabledOrDeclaredForUid(int uid,
            @NonNull PackageManagerInternal packageManager) {
        final int userId = UserHandle.getUserId(uid);
        for (AndroidPackage pkg : packageManager.getPackagesForAppId(UserHandle.getAppId(uid))) {
            if (isEnabledOrDeclared(pkg.getPackageName(), userId, packageManager)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the stored value changed. */
    public boolean setEnabled(@NonNull String packageName, int userId, boolean enabled) {
        synchronized (mLock) {
            ArrayMap<String, Boolean> packages = mPackagesByUser.get(userId);
            if (packages == null) {
                packages = new ArrayMap<>();
                mPackagesByUser.put(userId, packages);
            }
            final Boolean oldValue = packages.put(packageName, enabled);
            final boolean changed = oldValue == null || oldValue != enabled;
            if (changed) {
                writeLocked();
            }
            return changed;
        }
    }

    private void readLocked() {
        try (FileInputStream input = mFile.openRead()) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(input);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG || !TAG_PACKAGE.equals(parser.getName())) {
                    continue;
                }
                final String packageName = parser.getAttributeValue(null, ATTR_NAME);
                final int userId = parser.getAttributeInt(null, ATTR_USER_ID);
                final boolean enabled = parser.getAttributeBoolean(null, ATTR_ENABLED, true);
                if (packageName == null || packageName.isEmpty() || userId < 0) {
                    Slog.w(TAG, "Ignoring invalid entry in " + mFile.getBaseFile());
                    continue;
                }
                ArrayMap<String, Boolean> packages = mPackagesByUser.get(userId);
                if (packages == null) {
                    packages = new ArrayMap<>();
                    mPackagesByUser.put(userId, packages);
                }
                packages.put(packageName, enabled);
            }
        } catch (FileNotFoundException ignored) {
            // The file is created on the first state change.
        } catch (Exception e) {
            Slog.wtf(TAG, "Failed to read " + mFile.getBaseFile(), e);
            mPackagesByUser.clear();
        }
    }

    private void writeLocked() {
        FileOutputStream output = null;
        try {
            output = mFile.startWrite();
            final TypedXmlSerializer serializer = Xml.resolveSerializer(output);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_ROOT);
            for (int i = 0; i < mPackagesByUser.size(); i++) {
                final int userId = mPackagesByUser.keyAt(i);
                final ArrayMap<String, Boolean> packages = mPackagesByUser.valueAt(i);
                for (int j = 0; j < packages.size(); j++) {
                    serializer.startTag(null, TAG_PACKAGE);
                    serializer.attribute(null, ATTR_NAME, packages.keyAt(j));
                    serializer.attributeInt(null, ATTR_USER_ID, userId);
                    serializer.attributeBoolean(null, ATTR_ENABLED, packages.valueAt(j));
                    serializer.endTag(null, TAG_PACKAGE);
                }
            }
            serializer.endTag(null, TAG_ROOT);
            serializer.endDocument();
            mFile.finishWrite(output);
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to write " + mFile.getBaseFile(), e);
            if (output != null) {
                mFile.failWrite(output);
            }
        }
    }
}
