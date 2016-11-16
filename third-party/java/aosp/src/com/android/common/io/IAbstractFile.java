/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.common.io;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A file.
 */
public interface IAbstractFile extends IAbstractResource {
    enum PreferredWriteMode {
        INPUTSTREAM, OUTPUTSTREAM
    }

    /**
     * Returns an {@link InputStream} object on the file content.
     *
     * The stream must be closed by the caller.
     *
     * @throws StreamException
     */
    InputStream getContents() throws StreamException;

    /**
     * Sets the content of the file.
     * @param source the content
     * @throws StreamException
     */
    void setContents(InputStream source) throws StreamException;

    /**
     * Returns an {@link OutputStream} to write into the file.
     * @throws StreamException
     */
    OutputStream getOutputStream() throws StreamException;

    /**
     * Returns the preferred mode to write into the file.
     */
    PreferredWriteMode getPreferredWriteMode();

    /**
     * Returns the last modification timestamp
     */
    long getModificationStamp();
}
