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
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.objectweb.asm.ClassWriter;

public class JavaFileManagerStubJarWriter implements StubJarWriter {
  private final JavaFileManager fileManager;

  public JavaFileManagerStubJarWriter(JavaFileManager fileManager) throws IOException {
    this.fileManager = fileManager;
  }

  @Override
  public void writeResource(Path relativePath, InputStream resourceContents) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void writeClass(Path relativePath, Consumer<ClassWriter> stubber) throws IOException {
    String relativePathString = MorePaths.pathWithUnixSeparators(relativePath);
    String className =
        relativePathString
            .replace('/', '.')
            .substring(0, relativePathString.length() - 6); // Strip .class off the end

    ClassWriter writer = new ClassWriter(0);
    stubber.accept(writer);
    try (InputStream inputStream = new ByteArrayInputStream(writer.toByteArray());
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
