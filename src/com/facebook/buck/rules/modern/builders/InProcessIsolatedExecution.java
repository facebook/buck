/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.io.MoreFiles;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** IsolatedExecution implementation that will run buildrules within the current buck process. */
class InProcessIsolatedExecution implements IsolatedExecution {
  private final NamedTemporaryDirectory workDir;
  private final BuckEventBus eventBus;
  private final Console console;
  private final LocalContentAddressedStorage storage;

  InProcessIsolatedExecution(BuckEventBus eventBus, Console console) throws IOException {
    this.eventBus = eventBus;
    this.console = console;
    this.workDir = new NamedTemporaryDirectory("__work__");
    this.storage =
        new LocalContentAddressedStorage(
            workDir.getPath().resolve("__cache__"), InputsDigestBuilder::defaultDigestForStruct);
  }

  @Override
  public void close() throws IOException {
    workDir.close();
  }

  @Override
  public void build(
      ExecutionContext executionContext,
      InputsDigestBuilder inputsBuilder,
      Set<Path> outputs,
      Path projectRoot,
      HashCode hash,
      BuildTarget buildTarget,
      Path cellPrefixRoot)
      throws IOException, StepFailedException, InterruptedException {
    String dirName =
        String.format(
            "%.40s-%d",
            buildTarget.getShortNameAndFlavorPostfix(),
            buildTarget.getFullyQualifiedName().hashCode());
    Path buildDir = workDir.getPath().resolve(dirName);

    try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
      Inputs inputs = inputsBuilder.build();
      storage.addMissing(inputs.getRequiredData());
      storage.materializeInputs(buildDir, inputs.getRootDigest());
      new IsolatedBuildableBuilder(buildDir, projectRoot) {
        @Override
        protected Console createConsole() {
          return console;
        }

        @Override
        protected BuckEventBus createEventBus(Console console) {
          return eventBus;
        }
      }.build(hash);
      materializeOutputs(outputs, buildDir, cellPrefixRoot);
    }
  }

  private void materializeOutputs(Iterable<Path> outputs, Path buildDir, Path materializeDir) {
    for (Path path : outputs) {
      Preconditions.checkState(!path.isAbsolute());
      try {
        Path dest = materializeDir.resolve(path);
        Path source = buildDir.resolve(path);
        MoreFiles.createParentDirectories(dest);
        if (Files.isDirectory(source)) {
          MostFiles.copyRecursively(source, dest);
        } else {
          Files.copy(source, dest);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
