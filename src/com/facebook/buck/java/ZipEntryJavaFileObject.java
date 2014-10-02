/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.io.CharStreams;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.concurrent.GuardedBy;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import javax.annotation.Nullable;

class ZipEntryJavaFileObject extends SimpleJavaFileObject implements Closeable {

  private final ZipFile zipFile;
  private final ZipEntry zipEntry;

  @GuardedBy("this")
  @Nullable
  private String contents;

  public ZipEntryJavaFileObject(ZipFile zipFile, ZipEntry zipEntry) {
    super(createURIFromEntry(zipEntry), JavaFileObject.Kind.SOURCE);
    this.zipFile = Preconditions.checkNotNull(zipFile);
    this.zipEntry = Preconditions.checkNotNull(zipEntry);
  }

  /**
   * Creates a canonical URI that represents the {@link ZipEntry}. This URI starts with
   * {@code "string:///"} because {@link JavaCompiler} does not seem to tolerate URIs that start
   * with {@code "jar:///"}, even though that would be more appropriate.
   */
  private static URI createURIFromEntry(ZipEntry entry) {
    try {
      return new URI("string:///" + entry.getName());
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the contents of the {@link ZipEntry} as a string. Ensures that the entry is read at
   * most once.
   */
  private synchronized String getContentsAsString() {
    if (contents != null) {
      return contents;
    }

    try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
      contents = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return contents;
  }

  /**
   * Closes the {@link ZipFile} this entry was loaded from. Use with care.
   */
  @Override
  public void close() throws IOException {
    zipFile.close();
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) {
    return getContentsAsString();
  }
}
