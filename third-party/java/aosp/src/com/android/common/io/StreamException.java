/*
 * Copyright (C) 2010 The Android Open Source Project
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


/**
 * Exception thrown when {@link IAbstractFile#getContents()} fails.
 */
public class StreamException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Error {
        DEFAULT, OUTOFSYNC, FILENOTFOUND
    }

    private final  Error mError;
    private final IAbstractFile mFile;

    public StreamException(Exception e, IAbstractFile file) {
        this(e, file, Error.DEFAULT);
    }

    public StreamException(Exception e, IAbstractFile file, Error error) {
        super(e);
        mFile = file;
        mError = error;
    }

    public Error getError() {
        return mError;
    }

    public IAbstractFile getFile() {
        return mFile;
    }
}
