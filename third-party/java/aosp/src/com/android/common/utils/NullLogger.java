/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.common.utils;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

/**
 * <p>
 * Dummy implementation of an {@link ILogger}.
 * </p>
 * Use {@link #getLogger()} to get a default instance of this {@link NullLogger}.
 */
public class NullLogger implements ILogger {

    private static final ILogger sThis = new NullLogger();

    public static ILogger getLogger() {
        return sThis;
    }

    @Override
    public void error(@Nullable Throwable t, @Nullable String errorFormat, Object... args) {
        // ignore
    }

    @Override
    public void warning(@NonNull String warningFormat, Object... args) {
        // ignore
    }

    @Override
    public void info(@NonNull String msgFormat, Object... args) {
        // ignore
    }

    @Override
    public void verbose(@NonNull String msgFormat, Object... args) {
        // ignore
    }

}
