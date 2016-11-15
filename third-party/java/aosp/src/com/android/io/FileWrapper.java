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

package com.android.io;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

/**
 * An implementation of {@link IAbstractFile} extending {@link File}.
 */
public class FileWrapper extends File implements IAbstractFile {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new File instance matching a given {@link File} object.
     * @param file the file to match
     */
    public FileWrapper(File file) {
        super(file.getAbsolutePath());
    }

    /**
     * Creates a new File instance from a parent abstract pathname and a child pathname string.
     * @param parent the parent pathname
     * @param child the child name
     *
     * @see File#File(File, String)
     */
    public FileWrapper(File parent, String child) {
        super(parent, child);
    }

    /**
     * Creates a new File instance by converting the given pathname string into an abstract
     * pathname.
     * @param osPathname the OS pathname
     *
     * @see File#File(String)
     */
    public FileWrapper(String osPathname) {
        super(osPathname);
    }

    /**
     * Creates a new File instance from a parent abstract pathname and a child pathname string.
     * @param parent the parent pathname
     * @param child the child name
     *
     * @see File#File(String, String)
     */
    public FileWrapper(String parent, String child) {
        super(parent, child);
    }

    /**
     * Creates a new File instance by converting the given <code>file:</code> URI into an
     * abstract pathname.
     * @param uri An absolute, hierarchical URI with a scheme equal to "file", a non-empty path
     * component, and undefined authority, query, and fragment components
     *
     * @see File#File(URI)
     */
    public FileWrapper(URI uri) {
        super(uri);
    }

    @Override
    public InputStream getContents() throws StreamException {
        try {
            return new FileInputStream(this);
        } catch (FileNotFoundException e) {
            throw new StreamException(e, this, StreamException.Error.FILENOTFOUND);
        }
    }

    @Override
    public void setContents(InputStream source) throws StreamException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(this);

            byte[] buffer = new byte[1024];
            int count = 0;
            while ((count = source.read(buffer)) != -1) {
                fos.write(buffer, 0, count);
            }
        } catch (IOException e) {
            throw new StreamException(e, this);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    throw new StreamException(e, this);
                }
            }
        }
    }

    @Override
    public OutputStream getOutputStream() throws StreamException {
        try {
            return new FileOutputStream(this);
        } catch (FileNotFoundException e) {
            throw new StreamException(e, this);
        }
    }

    @Override
    public PreferredWriteMode getPreferredWriteMode() {
        return PreferredWriteMode.OUTPUTSTREAM;
    }

    @Override
    public String getOsLocation() {
        return getAbsolutePath();
    }

    @Override
    public boolean exists() {
        return isFile();
    }

    @Override
    public long getModificationStamp() {
        return lastModified();
    }

    @Override
    public IAbstractFolder getParentFolder() {
        String p = this.getParent();
        if (p == null) {
            return null;
        }
        return new FolderWrapper(p);
    }
}
