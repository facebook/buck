/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.multidex;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A zip element.
 */
class ArchivePathElement implements ClassPathElement {

    private ZipFile archive;

    public ArchivePathElement(ZipFile archive) {
        this.archive = archive;
    }

    @Override
    public InputStream open(String path) throws IOException {
        ZipEntry entry = archive.getEntry(path);
        if (entry == null) {
            throw new FileNotFoundException(path);
        } else {
            return archive.getInputStream(entry);
        }
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }

}
