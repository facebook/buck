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
import com.facebook.buck.rules.modern.builders.FileTreeBuilder.InputFile;
import com.facebook.buck.rules.modern.builders.FileTreeBuilder.ProtocolTreeBuilder;
import com.facebook.buck.rules.modern.builders.Protocol.Digest;
import com.facebook.buck.rules.modern.builders.Protocol.Directory;
import com.facebook.buck.rules.modern.builders.Protocol.OutputDirectory;
import com.facebook.buck.rules.modern.builders.Protocol.OutputFile;
import com.facebook.buck.rules.modern.builders.Protocol.Tree;
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
  public static OutOfProcessIsolatedExecution create(Protocol protocol) throws IOException {
    NamedTemporaryDirectory workDir = new NamedTemporaryDirectory("__work__");
    LocalContentAddressedStorage storage =
        new LocalContentAddressedStorage(workDir.getPath().resolve("__cache__"), protocol);
    return new OutOfProcessIsolatedExecution(workDir, storage, protocol);
  }

  private OutOfProcessIsolatedExecution(
      NamedTemporaryDirectory workDir, LocalContentAddressedStorage storage, Protocol protocol)
      throws IOException {
    super();
    this.storage = storage;
    this.executionService =
        new RemoteExecutionService() {
          @Override
          public ExecutionResult execute(
              ImmutableList<String> command,
              ImmutableSortedMap<String, String> commandEnvironment,
              Digest inputsRootDigest,
              Set<Path> outputs)
              throws IOException, InterruptedException {
            Path buildDir = workDir.getPath().resolve(inputsRootDigest.getHash());
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

              return new ExecutionResult() {
                @Override
                public ImmutableList<OutputDirectory> getOutputDirectories() {
                  return outputDirsBuilder.build();
                }

                @Override
                public ImmutableList<OutputFile> getOutputFiles() {
                  return outputFilesBuilder.build();
                }

                @Override
                public int getExitCode() {
                  return result.getExitCode();
                }

                @Override
                public Optional<String> getStderr() {
                  return result.getStderr();
                }
              };
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
                FileTreeBuilder builder = new FileTreeBuilder();

                try (Stream<Path> contents = Files.walk(path)) {
                  RichStream.from(contents)
                      .forEachThrowing(
                          entry -> {
                            if (Files.isRegularFile(entry)) {
                              builder.addFile(
                                  path.relativize(entry),
                                  () ->
                                      new InputFile(
                                          hashFile(entry).toString(),
                                          (int) Files.size(entry),
                                          Files.isExecutable(entry),
                                          () -> new FileInputStream(entry.toFile())));
                            }
                          });
                }

                List<Directory> directories = new ArrayList<>();
                Digest digest =
                    builder.buildTree(
                        new ProtocolTreeBuilder(
                            requiredDataBuilder::put, directories::add, protocol));
                Preconditions.checkState(!directories.isEmpty());
                Tree tree = protocol.newTree(directories.get(directories.size() - 1), directories);
                byte[] treeData = protocol.toByteArray(tree);
                Digest treeDigest = protocol.computeDigest(treeData);

                outputDirsBuilder.add(protocol.newOutputDirectory(output, digest, treeDigest));
                requiredDataBuilder.put(treeDigest, () -> new ByteArrayInputStream(treeData));
              } else {
                long size = Files.size(path);
                boolean isExecutable = Files.isExecutable(path);
                Digest digest = protocol.newDigest(hashFile(path).toString(), (int) size);

                ThrowingSupplier<InputStream, IOException> dataSupplier =
                    () -> new FileInputStream(path.toFile());
                outputFilesBuilder.add(
                    protocol.newOutputFile(output, digest, isExecutable, dataSupplier));
                requiredDataBuilder.put(digest, dataSupplier);
              }
            }
          }

          public HashCode hashFile(Path file) throws IOException {
            return MoreFiles.asByteSource(file).hash(Hashing.sha1());
          }
        };
    this.protocol = protocol;
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
