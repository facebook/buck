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

import com.android.common.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import java.io.PrintStream;
import java.util.Formatter;


/**
 * <p>
 * An implementation of {@link ILogger} that prints to {@link System#out} and {@link System#err}.
 * </p>
 *
 */
public class StdLogger implements ILogger {

    private final Level mLevel;

    public enum Level {
        VERBOSE(0),
        INFO(1),
        WARNING(2),
        ERROR(3);

        private final int mLevel;

        Level(int level) {
            mLevel = level;
        }
    }

    /**
     * Creates the {@link StdLogger} with a given log {@link Level}.
     * @param level the log Level.
     */
    public StdLogger(@NonNull Level level) {
        if (level == null) {
            throw new IllegalArgumentException("level cannot be null");
        }

        mLevel = level;
    }

    /**
     * Returns the logger's log {@link Level}.
     * @return the log level.
     */
    public Level getLevel() {
        return mLevel;
    }

    /**
     * <p>
     * Prints an error message.
     * </p>
     * <p>
     * The message will be tagged with "Error" on the output so the caller does not
     * need to put such a prefix in the format string.
     * </p>
     * <p>
     * The output is done on {@link System#err}.
     * </p>
     * This is always displayed, independent of the logging {@link Level}.
     *
     * @param t is an optional {@link Throwable} or {@link Exception}. If non-null, it's
     *          message will be printed out.
     * @param errorFormat is an optional error format. If non-null, it will be printed
     *          using a {@link Formatter} with the provided arguments.
     * @param args provides the arguments for errorFormat.
     */
    @Override
    public void error(@Nullable Throwable t, @Nullable String errorFormat, Object... args) {
        if (errorFormat != null) {
            String msg = String.format("Error: " + errorFormat, args);

            printMessage(msg, System.err);
        }
        if (t != null) {
            System.err.println(String.format("Error: %1$s", t.getMessage()));
        }
    }

    /**
     * <p>
     * Prints a warning message.
     * </p>
     * <p>
     * The message will be tagged with "Warning" on the output so the caller does not
     * need to put such a prefix in the format string.
     * </p>
     * <p>
     * The output is done on {@link System#out}.
     * </p>
     * <p>
     * This is displayed only if the logging {@link Level} is {@link Level#WARNING} or higher.
     *
     * @param warningFormat is a string format to be used with a {@link Formatter}. Cannot be null.
     * @param args provides the arguments for warningFormat.
     */
    @Override
    public void warning(@NonNull String warningFormat, Object... args) {
        if (mLevel.mLevel > Level.WARNING.mLevel) {
            return;
        }

        String msg = String.format("Warning: " + warningFormat, args);

        printMessage(msg, System.out);
    }

    /**
     * <p>
     * Prints an info message.
     * </p>
     * <p>
     * The output is done on {@link System#out}.
     * </p>
     * <p>
     * This is displayed only if the logging {@link Level} is {@link Level#INFO} or higher.
     *
     * @param msgFormat is a string format to be used with a {@link Formatter}. Cannot be null.
     * @param args provides the arguments for msgFormat.
     */
    @Override
    public void info(@NonNull String msgFormat, Object... args) {
        if (mLevel.mLevel > Level.INFO.mLevel) {
            return;
        }

        String msg = String.format(msgFormat, args);

        printMessage(msg, System.out);
    }

    /**
     * <p>
     * Prints a verbose message.
     * </p>
     * <p>
     * The output is done on {@link System#out}.
     * </p>
     * <p>
     * This is displayed only if the logging {@link Level} is {@link Level#VERBOSE} or higher.
     * </p>
     * @param msgFormat is a string format to be used with a {@link Formatter}. Cannot be null.
     * @param args provides the arguments for msgFormat.
     */
    @Override
    public void verbose(@NonNull String msgFormat, Object... args) {
        if (mLevel.mLevel > Level.VERBOSE.mLevel) {
            return;
        }

        String msg = String.format(msgFormat, args);

        printMessage(msg, System.out);
    }

    private void printMessage(String msg, PrintStream stream) {
        if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_WINDOWS &&
                !msg.endsWith("\r\n") &&
                msg.endsWith("\n")) {
            // remove last \n so that println can use \r\n as needed.
            msg = msg.substring(0, msg.length() - 1);
        }

        stream.print(msg);

        if (!msg.endsWith("\n")) {
            stream.println();
        }
    }

}
