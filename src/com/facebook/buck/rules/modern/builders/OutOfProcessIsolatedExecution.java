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

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.rules.modern.builders.thrift.ActionResult;
import com.facebook.buck.rules.modern.builders.thrift.Digest;
import com.facebook.buck.rules.modern.builders.thrift.OutputDirectory;
import com.facebook.buck.rules.modern.builders.thrift.OutputFile;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams.Builder;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/** IsolatedExecution implementation that will run buildrules in a subprocess. */
public class OutOfProcessIsolatedExecution extends RemoteExecution {
  private static final long MAX_INLINED_SIZE_BYTES = 1000;
  private final NamedTemporaryDirectory workDir;

  /**
   * Returns a RemoteExecution implementation that uses a local CAS and a separate local temporary
   * directory for execution.
   */
  public static OutOfProcessIsolatedExecution create() throws IOException {
    NamedTemporaryDirectory workDir = new NamedTemporaryDirectory("__work__");
    LocalContentAddressedStorage storage =
        new LocalContentAddressedStorage(
            workDir.getPath().resolve("__cache__"), InputsDigestBuilder::defaultDigestForStruct);
    return new OutOfProcessIsolatedExecution(workDir, storage);
  }

  private OutOfProcessIsolatedExecution(
      NamedTemporaryDirectory workDir, LocalContentAddressedStorage storage) throws IOException {
    super(
        storage,
        new RemoteExecutionService() {
          @Override
          public ActionResult execute(
              ImmutableList<String> command,
              ImmutableSortedMap<String, String> commandEnvironment,
              Digest inputsRootDigest,
              Set<Path> outputs)
              throws IOException, InterruptedException {
            Path buildDir = workDir.getPath().resolve(inputsRootDigest.hash);
            try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
              storage.materializeInputs(buildDir, inputsRootDigest);

              Builder paramsBuilder = ProcessExecutorParams.builder();
              paramsBuilder.setCommand(command);
              paramsBuilder.setEnvironment(commandEnvironment);
              paramsBuilder.setDirectory(buildDir);
              CapturingPrintStream stdOut = new CapturingPrintStream();
              CapturingPrintStream stdErr = new CapturingPrintStream();
              Console console =
                  new Console(Verbosity.STANDARD_INFORMATION, stdOut, stdErr, Ansi.withoutTty());
              Result result =
                  new DefaultProcessExecutor(console).launchAndExecute(paramsBuilder.build());

              // TODO(cjhopman): Should outputs be returned on failure?
              ImmutableList.Builder<OutputFile> outputFilesBuilder = ImmutableList.builder();
              ImmutableList.Builder<OutputDirectory> outputDirsBuilder = ImmutableList.builder();
              if (result.getExitCode() == 0) {
                ImmutableMap.Builder<Digest, ThrowingSupplier<InputStream, IOException>>
                    requiredDataBuilder = ImmutableMap.builder();

                collectOutputs(
                    outputs, buildDir, outputFilesBuilder, outputDirsBuilder, requiredDataBuilder);
                storage.addMissing(requiredDataBuilder.build());
              }

              return new ActionResult(
                  outputFilesBuilder.build(),
                  outputDirsBuilder.build(),
                  result.getExitCode(),
                  null,
                  null,
                  ByteBuffer.wrap(result.getStderr().get().getBytes(Charsets.UTF_8)),
                  null);
            }
          }

          public void collectOutputs(
              Set<Path> outputs,
              Path buildDir,
              ImmutableList.Builder<OutputFile> outputFilesBuilder,
              ImmutableList.Builder<OutputDirectory> outputDirsBuilder,
              ImmutableMap.Builder<Digest, ThrowingSupplier<InputStream, IOException>>
                  requiredDataBuilder)
              throws IOException {
            for (Path output : outputs) {
              Path path = buildDir.resolve(output);
              Preconditions.checkState(Files.exists(path));
              if (Files.isDirectory(path)) {
                InputsDigestBuilder builder =
                    InputsDigestBuilder.createDefault(path, this::hashFile);

                try (Stream<Path> contents = Files.walk(path)) {
                  RichStream.from(contents)
                      .forEachThrowing(
                          entry -> {
                            if (Files.isRegularFile(entry)) {
                              builder.addFile(path.relativize(entry), Files.isExecutable(entry));
                            }
                          });
                }

                Inputs inputs = builder.build();
                outputDirsBuilder.add(
                    new OutputDirectory(output.toString(), inputs.getTreeDigest()));
                requiredDataBuilder.putAll(inputs.getRequiredData());
              } else {
                long size = Files.size(path);
                Digest digest = new Digest(hashFile(path).toString(), size);
                boolean isExecutable = Files.isExecutable(path);
                ByteBuffer content =
                    size < MAX_INLINED_SIZE_BYTES
                        ? ByteBuffer.wrap(Files.readAllBytes(path))
                        : null;
                outputFilesBuilder.add(
                    new OutputFile(output.toString(), digest, content, isExecutable));
                requiredDataBuilder.put(digest, () -> Files.newInputStream(path));
              }
            }
          }

          public HashCode hashFile(Path file) throws IOException {
            return MoreFiles.asByteSource(file).hash(Hashing.sha1());
          }
        });
    this.workDir = workDir;
  }

  @Override
  public void close() throws IOException {
    workDir.close();
  }
}
