/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testutil;

import com.google.common.io.ByteStreams;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class TestJar implements Closeable {
  private final JarFile jarFile;

  public TestJar(File file) throws IOException {
    jarFile = new JarFile(file);
  }

  @Override
  public void close() throws IOException {
    jarFile.close();
  }

  public List<? extends ZipEntry> getZipEntries() {
    return jarFile.stream().collect(Collectors.toList());
  }

  public List<String> getEntriesContent() {
    List<String> result = new ArrayList<>();
    for (ZipEntry zipEntry : getZipEntries()) {
      try (InputStream entryStream = jarFile.getInputStream(zipEntry)) {
        result.add(new String(ByteStreams.toByteArray(entryStream), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
    return result;
  }
}
