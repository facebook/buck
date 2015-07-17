/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.dx.command;

import java.io.PrintStream;

/**
 * Provides standard and error PrintStream object to output information.<br>
 * By default the PrintStream objects link to {@code System.out} and
 * {@code System.err} but they can be changed to link to other
 * PrintStream.
 */
public class DxConsole {
    /**
     * Standard output stream. Links to {@code System.out} by default.
     */
    public PrintStream out = System.out;

    /**
     * Error output stream. Links to {@code System.err} by default.
     */
    public PrintStream err = System.err;
}
