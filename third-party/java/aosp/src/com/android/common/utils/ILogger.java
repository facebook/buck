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

import java.util.Formatter;

/**
 * <p>
 * Interface used to display warnings/errors while parsing the SDK content.
 * </p>
 * There are a few default implementations available:
 * <ul>
 * <li> {@link NullLogger} is an implementation that does <em>nothing</em> with the log.
 *  Useful for limited cases where you need to call a class that requires a non-null logging
 *  yet the calling code does not have any mean of reporting logs itself. It can be
 *  acceptable for use as a temporary implementation but most of the time that means the caller
 *  code needs to be reworked to take a logger object from its own caller.
 * </li>
 * <li> {@link StdLogger} is an implementation that dumps the log to {@link System#out} or
 *  {@link System#err}. This is useful for unit tests or code that does not have any GUI.
 *  GUI based apps based should not use it and should provide a better way to report to the user.
 * </li>
 * </ul>
 */
public interface ILogger {

    /**
     * Prints an error message.
     *
     * @param t is an optional {@link Throwable} or {@link Exception}. If non-null, its
     *          message will be printed out.
     * @param msgFormat is an optional error format. If non-null, it will be printed
     *          using a {@link Formatter} with the provided arguments.
     * @param args provides the arguments for errorFormat.
     */
    void error(@Nullable Throwable t, @Nullable String msgFormat, Object... args);

    /**
     * Prints a warning message.
     *
     * @param msgFormat is a string format to be used with a {@link Formatter}. Cannot be null.
     * @param args provides the arguments for warningFormat.
     */
    void warning(@NonNull String msgFormat, Object... args);

    /**
     * Prints an information message.
     *
     * @param msgFormat is a string format to be used with a {@link Formatter}. Cannot be null.
     * @param args provides the arguments for msgFormat.
     */
    void info(@NonNull String msgFormat, Object... args);

    /**
     * Prints a verbose message.
     *
     * @param msgFormat is a string format to be used with a {@link Formatter}. Cannot be null.
     * @param args provides the arguments for msgFormat.
     */
    void verbose(@NonNull String msgFormat, Object... args);

}
