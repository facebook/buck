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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Supplier;

@BuckStyleValue
public abstract class WriteFileStep extends DelegateStep<WriteFileIsolatedStep> {

  abstract AbsPath getRootPath();

  abstract ByteSource getSource();

  abstract Path getOutputPath();

  abstract boolean getExecutable();

  @Override
  public String getShortNameSuffix() {
    return "write_file";
  }

  @Override
  public WriteFileIsolatedStep createDelegate(StepExecutionContext context) {
    return WriteFileIsolatedStep.of(getRootPath(), getSource(), getOutputPath(), getExecutable());
  }

  public static WriteFileStep of(
      AbsPath rootPath, ByteSource content, Path outputPath, boolean executable) {
    Preconditions.checkArgument(
        !outputPath.isAbsolute(), "Output path must not be absolute: %s", outputPath);
    return ImmutableWriteFileStep.ofImpl(rootPath, content, outputPath, executable);
  }

  public static WriteFileStep of(
      AbsPath rootPath, ByteSource content, RelPath outputPath, boolean executable) {
    return ImmutableWriteFileStep.ofImpl(rootPath, content, outputPath.getPath(), executable);
  }

  public static WriteFileStep of(
      AbsPath rootPath, String content, Path outputPath, boolean executable) {
    return of(rootPath, Suppliers.ofInstance(content), outputPath, executable);
  }

  public static WriteFileStep of(
      AbsPath rootPath, Supplier<String> content, Path outputPath, boolean executable) {
    return ImmutableWriteFileStep.ofImpl(
        rootPath,
        new ByteSource() {
          @Override
          public InputStream openStream() {
            // echo by default writes a trailing new line and so should we.
            return new ByteArrayInputStream(
                (content.get() + "\n").getBytes(StandardCharsets.UTF_8));
          }
        },
        outputPath,
        executable);
  }

  public static WriteFileStep of(
      AbsPath rootPath, Supplier<String> content, RelPath outputPath, boolean executable) {
    return of(rootPath, content, outputPath.getPath(), executable);
  }

  public static WriteFileStep of(
      AbsPath rootPath, String content, RelPath outputPath, boolean executable) {
    return of(rootPath, content, outputPath.getPath(), executable);
  }
}
