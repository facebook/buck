/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.common.ide.common.blame;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.google.common.base.Objects;

import java.io.File;

/**
 * Represents a source file.
 */
@Immutable
public final class SourceFile {

    @NonNull
    public static final SourceFile UNKNOWN = new SourceFile();

    @Nullable
    private final File mSourceFile;

    /**
     * A human readable description
     *
     * Usually the file name is OK for the short output, but for the manifest merger,
     * where all of the files will be named AndroidManifest.xml the variant name is more useful.
     */
    @Nullable
    private final String mDescription;

    @SuppressWarnings("NullableProblems")
    public SourceFile(
            @NonNull File sourceFile,
            @NonNull String description) {
        mSourceFile = sourceFile;
        mDescription = description;
    }

    public SourceFile(
            @SuppressWarnings("NullableProblems") @NonNull File sourceFile) {
        mSourceFile = sourceFile;
        mDescription = null;
    }

    public SourceFile(
            @SuppressWarnings("NullableProblems") @NonNull String description) {
        mSourceFile = null;
        mDescription = description;
    }

    private SourceFile() {
        mSourceFile = null;
        mDescription = null;
    }

    @Nullable
    public File getSourceFile() {
        return mSourceFile;
    }

    @Nullable
    public String getDescription() {
        return mDescription;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SourceFile)) {
            return false;
        }
        SourceFile other = (SourceFile) obj;

        return Objects.equal(mDescription, other.mDescription) &&
                Objects.equal(mSourceFile, other.mSourceFile);
    }

    @Override
    public int hashCode() {
        String filePath = mSourceFile != null ? mSourceFile.getPath() : null;
        return Objects.hashCode(filePath, mDescription);
    }

    @Override
    @NonNull
    public String toString() {
        return print(false /* shortFormat */);
    }

    @NonNull
    public String print(boolean shortFormat) {
        if (mSourceFile == null) {
            if (mDescription == null) {
                return "Unknown source file";
            }
            return mDescription;
        }
        String fileName = mSourceFile.getName();
        String fileDisplayName = shortFormat ? fileName : mSourceFile.getAbsolutePath();
        if (mDescription == null || mDescription.equals(fileName)) {
            return fileDisplayName;
        } else {
            return String.format("[%1$s] %2$s", mDescription, fileDisplayName);
        }
    }

}
