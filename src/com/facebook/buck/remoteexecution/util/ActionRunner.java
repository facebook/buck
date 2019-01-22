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

package com.facebook.buck.remoteexecution.util;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.FileNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Tree;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.MerkleTreeNode;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams.Builder;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.io.MoreFiles;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs an action (command + environment) in a directory and returns the results (exit code,
 * stdout/stderr, and outputs).
 */
public class ActionRunner {
  private final Protocol protocol;
  private final BuckEventBus eventBus;

  public ActionRunner(Protocol protocol, BuckEventBus eventBus) {
    this.protocol = protocol;
    this.eventBus = eventBus;
  }

  /** Results of an action. */
  public static class ActionResult {
    public final ImmutableList<OutputFile> outputFiles;
    public final ImmutableList<OutputDirectory> outputDirectories;
    public final ImmutableMap<Protocol.Digest, UploadDataSupplier> requiredData;
    public final int exitCode;
    public final String stderr;
    public final String stdout;

    ActionResult(
        ImmutableList<OutputFile> outputFiles,
        ImmutableList<OutputDirectory> outputDirectories,
        ImmutableMap<Digest, UploadDataSupplier> requiredData,
        int exitCode,
        String stderr,
        String stdout) {
      this.outputFiles = outputFiles;
      this.outputDirectories = outputDirectories;
      this.requiredData = requiredData;
      this.exitCode = exitCode;
      this.stderr = stderr;
      this.stdout = stdout;
    }
  }

  /** Runs an action and returns the result. */
  public ActionResult runAction(
      ImmutableList<String> command,
      ImmutableMap<String, String> environment,
      Set<Path> outputs,
      Path buildDir)
      throws IOException, InterruptedException {
    Console console;
    Builder paramsBuilder;
    try (Scope ignored = LeafEvents.scope(eventBus, "preparing_action")) {
      paramsBuilder = ProcessExecutorParams.builder();
      paramsBuilder.setCommand(command);
      paramsBuilder.setEnvironment(environment);
      paramsBuilder.setDirectory(buildDir);
      CapturingPrintStream stdOut = new CapturingPrintStream();
      CapturingPrintStream stdErr = new CapturingPrintStream();
      console = new Console(Verbosity.STANDARD_INFORMATION, stdOut, stdErr, Ansi.withoutTty());
    }

    Result result;
    try (Scope ignored = LeafEvents.scope(eventBus, "subprocess")) {
      result = new DefaultProcessExecutor(console).launchAndExecute(paramsBuilder.build());
    }

    ImmutableList.Builder<OutputFile> outputFiles;
    ImmutableList.Builder<OutputDirectory> outputDirectories;
    Map<Digest, UploadDataSupplier> requiredData = new HashMap<>();
    try (Scope ignored = LeafEvents.scope(eventBus, "collecting_outputs")) {
      outputFiles = ImmutableList.builder();
      outputDirectories = ImmutableList.builder();
      if (result.getExitCode() == 0) {
        // TODO(cjhopman): Should outputs be returned on failure?
        collectOutputs(outputs, buildDir, outputFiles, outputDirectories, requiredData);
      }
    }

    return new ActionResult(
        outputFiles.build(),
        outputDirectories.build(),
        ImmutableMap.copyOf(requiredData),
        result.getExitCode(),
        result.getStderr().get(),
        result.getStdout().get());
  }

  private void collectOutputs(
      Set<Path> outputs,
      Path buildDir,
      ImmutableList.Builder<OutputFile> outputFilesBuilder,
      ImmutableList.Builder<OutputDirectory> outputDirectoriesBuilder,
      Map<Digest, UploadDataSupplier> requiredDataBuilder)
      throws IOException {
    for (Path output : outputs) {
      Path path = buildDir.resolve(output);
      Preconditions.checkState(Files.exists(path));
      if (Files.isDirectory(path)) {
        Map<Path, FileNode> files = new HashMap<>();

        try (Stream<Path> contents = Files.walk(path)) {
          RichStream.from(contents)
              .forEachThrowing(
                  entry -> {
                    if (Files.isRegularFile(entry)) {
                      files.put(
                          path.relativize(entry),
                          protocol.newFileNode(
                              protocol.newDigest(
                                  hashFile(entry).toString(), (int) Files.size(entry)),
                              entry.getFileName().toString(),
                              Files.isExecutable(entry)));
                    }
                  });
        }

        MerkleTreeNodeCache merkleTreeCache = new MerkleTreeNodeCache(protocol);

        MerkleTreeNode node = merkleTreeCache.createNode(files, ImmutableMap.of());

        node.forAllFiles(
            path,
            (file, fileNode) ->
                requiredDataBuilder.put(
                    fileNode.getDigest(), () -> new FileInputStream(file.toFile())));

        List<Directory> directories = new ArrayList<>();
        merkleTreeCache.forAllData(node, data -> directories.add(data.getDirectory()));
        Preconditions.checkState(!directories.isEmpty());

        Tree tree = protocol.newTree(directories.get(directories.size() - 1), directories);
        byte[] treeData = protocol.toByteArray(tree);
        Digest treeDigest = protocol.computeDigest(treeData);

        outputDirectoriesBuilder.add(protocol.newOutputDirectory(output, treeDigest));
        requiredDataBuilder.put(treeDigest, () -> new ByteArrayInputStream(treeData));
      } else {
        long size = Files.size(path);
        boolean isExecutable = Files.isExecutable(path);
        Digest digest = protocol.newDigest(hashFile(path).toString(), (int) size);

        UploadDataSupplier dataSupplier = () -> new FileInputStream(path.toFile());
        outputFilesBuilder.add(protocol.newOutputFile(output, digest, isExecutable));
        requiredDataBuilder.put(digest, dataSupplier);
      }
    }
  }

  private HashCode hashFile(Path file) throws IOException {
    return MoreFiles.asByteSource(file).hash(protocol.getHashFunction());
  }
}
