/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

public class JavaFileManagerStubJarWriter implements StubJarWriter {
  private final JavaFileManager fileManager;

  public JavaFileManagerStubJarWriter(JavaFileManager fileManager) throws IOException {
    this.fileManager = fileManager;
  }

  @Override
  public void writeEntry(
      Path relativePath, ThrowingSupplier<InputStream, IOException> streamSupplier)
      throws IOException {
    String relativePathString = MorePaths.pathWithUnixSeparators(relativePath);
    Preconditions.checkArgument(relativePathString.endsWith(".class"));
    String className =
        relativePathString
            .replace('/', '.')
            .substring(0, relativePathString.length() - 6); // Strip .class off the end

    try (InputStream inputStream = streamSupplier.throwingGet();
        OutputStream outputStream =
            fileManager
                .getJavaFileForOutput(
                    StandardLocation.CLASS_OUTPUT, className, JavaFileObject.Kind.CLASS, null)
                .openOutputStream()) {
      ByteStreams.copy(inputStream, outputStream);
    }
  }

  @Override
  public void close() throws IOException {}
}
