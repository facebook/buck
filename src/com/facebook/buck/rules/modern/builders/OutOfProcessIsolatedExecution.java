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
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.rules.modern.builders.Protocol.Command;
import com.facebook.buck.rules.modern.builders.Protocol.OutputDirectory;
import com.facebook.buck.rules.modern.builders.Protocol.OutputFile;
import com.facebook.buck.rules.modern.builders.RemoteExecutionService.ExecutionResult;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.facebook.buck.util.Scope;
import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** IsolatedExecution implementation that will run buildrules in a subprocess. */
public class OutOfProcessIsolatedExecution extends RemoteExecution {

  private final NamedTemporaryDirectory workDir;
  private final LocalContentAddressedStorage storage;
  private final RemoteExecutionService executionService;
  private final Protocol protocol;

  /**
   * Returns a RemoteExecution implementation that uses a local CAS and a separate local temporary
   * directory for execution.
   */
  public static OutOfProcessIsolatedExecution create(Protocol protocol, BuckEventBus eventBus)
      throws IOException {
    NamedTemporaryDirectory workDir = new NamedTemporaryDirectory("__work__");
    LocalContentAddressedStorage storage =
        new LocalContentAddressedStorage(workDir.getPath().resolve("__cache__"), protocol);
    return new OutOfProcessIsolatedExecution(workDir, storage, protocol, eventBus);
  }

  private OutOfProcessIsolatedExecution(
      NamedTemporaryDirectory workDir,
      LocalContentAddressedStorage storage,
      final Protocol protocol,
      BuckEventBus eventBus)
      throws IOException {
    super(eventBus);
    this.storage = storage;
    this.protocol = protocol;
    this.executionService =
        (digest, inputsRootDigest, outputs) -> {
          Path buildDir = workDir.getPath().resolve(inputsRootDigest.getHash());
          try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
            Optional<Command> command;
            try (Scope ignored2 = LeafEvents.scope(getEventBus(), "materializing_inputs")) {
              command = storage.materializeInputs(buildDir, inputsRootDigest, Optional.of(digest));
            }

            ActionRunner.ActionResult actionResult =
                new ActionRunner(protocol, eventBus)
                    .runAction(
                        command.get().getCommand(),
                        command.get().getEnvironment(),
                        outputs,
                        buildDir);
            try (Scope ignored2 = LeafEvents.scope(getEventBus(), "uploading_results")) {
              storage.addMissing(actionResult.requiredData);
            }
            return new ExecutionResult() {
              @Override
              public ImmutableList<OutputDirectory> getOutputDirectories() {
                return actionResult.outputDirectories;
              }

              @Override
              public ImmutableList<OutputFile> getOutputFiles() {
                return actionResult.outputFiles;
              }

              @Override
              public int getExitCode() {
                return actionResult.exitCode;
              }

              @Override
              public Optional<String> getStderr() {
                return Optional.of(actionResult.stderr);
              }
            };
          }
        };
    this.workDir = workDir;
  }

  @Override
  protected Protocol getProtocol() {
    return protocol;
  }

  @Override
  protected ContentAddressedStorage getStorage() {
    return storage;
  }

  @Override
  protected RemoteExecutionService getExecutionService() {
    return executionService;
  }

  @Override
  public void close() throws IOException {
    workDir.close();
  }
}
