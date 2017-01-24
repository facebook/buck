/*
 * Copyright (C) 2011 The Android Open Source Project
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


public abstract class LineUtil {

    /**
     * Reformats a line so that it fits in 78 characters max.
     * <p/>
     * When wrapping the second line and following, prefix the string with a number of
     * spaces. This will use the first colon (:) to determine the prefix size
     * or use 4 as a minimum if there are no colons in the string.
     *
     * @param line The line to reflow. Must be non-null.
     * @return A new line to print as-is, that contains \n as needed.
     */
    public static String reflowLine(String line) {
        final int maxLen = 78;

        // Most of time the line will fit in the given length and this will be a no-op
        int n = line.length();
        int cr = line.indexOf('\n');
        if (n <= maxLen && (cr == -1 || cr == n - 1)) {
            return line;
        }

        int prefixSize = line.indexOf(':') + 1;
        // If there' some spacing after the colon, use the same when wrapping
        if (prefixSize > 0 && prefixSize < maxLen) {
            while(prefixSize < n && line.charAt(prefixSize) == ' ') {
                prefixSize++;
            }
        } else {
            prefixSize = 4;
        }
        String prefix = String.format(
                "%-" + Integer.toString(prefixSize) + "s",      //$NON-NLS-1$ //$NON-NLS-2$
                " ");                                           //$NON-NLS-1$

        StringBuilder output = new StringBuilder(n + prefixSize);

        while (n > 0) {
            cr = line.indexOf('\n');
            if (n <= maxLen && (cr == -1 || cr == n - 1)) {
                output.append(line);
                break;
            }

            // Line is longer than the max length, find the first character before and after
            // the whitespace where we want to break the line.
            int posNext = maxLen;
            if (cr != -1 && cr != n - 1 && cr <= posNext) {
                posNext = cr + 1;
                while (posNext < n && line.charAt(posNext) == '\n') {
                    posNext++;
                }
            }
            while (posNext < n && line.charAt(posNext) == ' ') {
                posNext++;
            }
            while (posNext > 0) {
                char c = line.charAt(posNext - 1);
                if (c != ' ' && c != '\n') {
                    posNext--;
                } else {
                    break;
                }
            }

            if (posNext == 0 || (posNext >= n && maxLen < n)) {
                // We found no whitespace separator. This should generally not occur.
                posNext = maxLen;
            }
            int posPrev = posNext;
            while (posPrev > 0) {
                char c = line.charAt(posPrev - 1);
                if (c == ' ' || c == '\n') {
                    posPrev--;
                } else {
                    break;
                }
            }

            output.append(line.substring(0, posPrev)).append('\n');
            line = prefix + line.substring(posNext);
            n = line.length();
        }

        return output.toString();
    }

    /**
     * Formats the string using {@link String#format(String, Object...)}
     * and then returns the result of {@link #reflowLine(String)}.
     *
     * @param format The string format.
     * @param params The parameters for the string format.
     * @return The result of {@link #reflowLine(String)} on the formatted string.
     */
    public static String reformatLine(String format, Object...params) {
        return reflowLine(String.format(format, params));
    }
}
