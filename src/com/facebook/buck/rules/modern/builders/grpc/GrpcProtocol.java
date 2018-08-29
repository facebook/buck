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

package com.facebook.buck.rules.modern.builders.grpc;

import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.OutputFile.Builder;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A Grpc-based Protocol implementation. */
public class GrpcProtocol implements Protocol {
  // TODO(shivanker): This hash function is only used to generate hashes of data we have in memory.
  // We still use the FileHashCache to compute hashes of source files. So until that is switched
  // over to SHA256, we cannot really change this.
  private static final HashFunction HASHER = Hashing.sha1();

  /** Wrapped Grpc Digest. */
  static class GrpcDigest implements Digest {
    private final build.bazel.remote.execution.v2.Digest digest;

    public GrpcDigest(build.bazel.remote.execution.v2.Digest digest) {
      this.digest = digest;
    }

    @Override
    public String getHash() {
      return digest.getHash();
    }

    @Override
    public int getSize() {
      return (int) digest.getSizeBytes();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof GrpcDigest)) {
        return false;
      }
      return ((GrpcDigest) obj).digest.equals(digest);
    }

    @Override
    public int hashCode() {
      return digest.hashCode();
    }
  }

  private static class GrpcCommand implements Command {
    private final build.bazel.remote.execution.v2.Command command;

    GrpcCommand(build.bazel.remote.execution.v2.Command command) {
      this.command = command;
    }

    @Override
    public ImmutableList<String> getCommand() {
      return command
          .getArgumentsList()
          .asByteStringList()
          .stream()
          .map(ByteString::toStringUtf8)
          .collect(ImmutableList.toImmutableList());
    }

    @Override
    public ImmutableMap<String, String> getEnvironment() {
      return command
          .getEnvironmentVariablesList()
          .stream()
          .collect(
              ImmutableMap.toImmutableMap(
                  EnvironmentVariable::getName, EnvironmentVariable::getValue));
    }

    @Override
    public ImmutableList<String> getOutputFiles() {
      return command
          .getOutputFilesList()
          .asByteStringList()
          .stream()
          .map(ByteString::toStringUtf8)
          .collect(ImmutableList.toImmutableList());
    }

    @Override
    public ImmutableList<String> getOutputDirectories() {
      return command
          .getOutputDirectoriesList()
          .asByteStringList()
          .stream()
          .map(ByteString::toStringUtf8)
          .collect(ImmutableList.toImmutableList());
    }
  }

  private static class GrpcAction implements Action {
    private final build.bazel.remote.execution.v2.Action action;

    GrpcAction(build.bazel.remote.execution.v2.Action action) {
      this.action = action;
    }

    @Override
    public Digest getCommandDigest() {
      return new GrpcDigest(action.getCommandDigest());
    }

    @Override
    public Digest getInputRootDigest() {
      return new GrpcDigest(action.getInputRootDigest());
    }
  }

  private static class GrpcFileNode implements FileNode {
    private final build.bazel.remote.execution.v2.FileNode fileNode;

    public GrpcFileNode(build.bazel.remote.execution.v2.FileNode fileNode) {
      this.fileNode = fileNode;
    }

    @Override
    public String getName() {
      return fileNode.getName();
    }

    @Override
    public Digest getDigest() {
      return new GrpcDigest(fileNode.getDigest());
    }

    @Override
    public boolean getIsExecutable() {
      return fileNode.getIsExecutable();
    }
  }

  private static class GrpcDirectory implements Directory {
    private final build.bazel.remote.execution.v2.Directory directory;

    public GrpcDirectory(build.bazel.remote.execution.v2.Directory directory) {
      this.directory = directory;
    }

    @Override
    public Iterable<FileNode> getFilesList() {
      return directory.getFilesList().stream().map(GrpcFileNode::new).collect(Collectors.toList());
    }

    @Override
    public Iterable<DirectoryNode> getDirectoriesList() {
      return directory
          .getDirectoriesList()
          .stream()
          .map(GrpcDirectoryNode::new)
          .collect(Collectors.toList());
    }

    @Override
    public Iterable<SymlinkNode> getSymlinksList() {
      return directory
          .getSymlinksList()
          .stream()
          .map(GrpcSymlinkNode::new)
          .collect(Collectors.toList());
    }
  }

  private static class GrpcSymlinkNode implements SymlinkNode {
    private final build.bazel.remote.execution.v2.SymlinkNode node;

    private GrpcSymlinkNode(build.bazel.remote.execution.v2.SymlinkNode node) {
      this.node = node;
    }

    @Override
    public String getName() {
      return node.getName();
    }

    @Override
    public String getTarget() {
      return node.getTarget();
    }
  }

  private static class GrpcDirectoryNode implements DirectoryNode {
    private final build.bazel.remote.execution.v2.DirectoryNode directoryNode;

    public GrpcDirectoryNode(build.bazel.remote.execution.v2.DirectoryNode directoryNode) {
      this.directoryNode = directoryNode;
    }

    @Override
    public String getName() {
      return directoryNode.getName();
    }

    @Override
    public Digest getDigest() {
      return new GrpcDigest(directoryNode.getDigest());
    }
  }

  private static class GrpcTree implements Tree {
    private final build.bazel.remote.execution.v2.Tree tree;

    public GrpcTree(build.bazel.remote.execution.v2.Tree tree) {
      this.tree = tree;
    }

    @Override
    public Iterable<Directory> getChildrenList() {
      return tree.getChildrenList().stream().map(GrpcDirectory::new).collect(Collectors.toList());
    }

    @Override
    public Directory getRoot() {
      return new GrpcDirectory(tree.getRoot());
    }
  }

  /** Wrapped Grpc OutputDirectory. */
  static class GrpcOutputDirectory implements OutputDirectory {
    private final build.bazel.remote.execution.v2.OutputDirectory outputDirectory;

    GrpcOutputDirectory(build.bazel.remote.execution.v2.OutputDirectory outputDirectory) {
      this.outputDirectory = outputDirectory;
    }

    @Override
    public String getPath() {
      return outputDirectory.getPath();
    }

    @Override
    public Digest getTreeDigest() {
      return new GrpcDigest(outputDirectory.getTreeDigest());
    }
  }

  /** Wrapped Grpc OutputFile. */
  static class GrpcOutputFile implements OutputFile {
    private final build.bazel.remote.execution.v2.OutputFile outputFile;

    GrpcOutputFile(build.bazel.remote.execution.v2.OutputFile outputFile) {
      this.outputFile = outputFile;
    }

    @Override
    public String getPath() {
      return outputFile.getPath();
    }

    @Override
    public Digest getDigest() {
      return new GrpcDigest(outputFile.getDigest());
    }

    @Override
    public boolean getIsExecutable() {
      return outputFile.getIsExecutable();
    }
  }

  @Override
  public Command parseCommand(ByteBuffer data) throws IOException {
    return new GrpcCommand(build.bazel.remote.execution.v2.Command.parseFrom(data));
  }

  @Override
  public Action parseAction(ByteBuffer data) throws IOException {
    return new GrpcAction(build.bazel.remote.execution.v2.Action.parseFrom(data));
  }

  @Override
  public Directory parseDirectory(ByteBuffer data) throws IOException {
    return new GrpcDirectory(build.bazel.remote.execution.v2.Directory.parseFrom(data));
  }

  @Override
  public Tree parseTree(ByteBuffer data) throws IOException {
    return new GrpcTree(build.bazel.remote.execution.v2.Tree.parseFrom(data));
  }

  @Override
  public Digest computeDigest(Directory directory) throws IOException {
    return computeDigest(toByteArray(directory));
  }

  @Override
  public Command newCommand(
      ImmutableList<String> command,
      ImmutableSortedMap<String, String> commandEnvironment,
      Set<Path> outputs) {
    List<String> outputStrings =
        outputs.stream().map(Path::toString).sorted().collect(Collectors.toList());

    return new GrpcCommand(
        build.bazel.remote.execution.v2.Command.newBuilder()
            .addAllArguments(command)
            .addAllEnvironmentVariables(
                commandEnvironment
                    .entrySet()
                    .stream()
                    .map(
                        entry ->
                            EnvironmentVariable.newBuilder()
                                .setName(entry.getKey())
                                .setValue(entry.getValue())
                                .build())
                    .collect(Collectors.toList()))
            .addAllOutputFiles(outputStrings)
            .addAllOutputDirectories(outputStrings)
            .build());
  }

  @Override
  public Action newAction(Digest commandDigest, Digest inputRootDigest) {
    return new GrpcAction(
        build.bazel.remote.execution.v2.Action.newBuilder()
            .setCommandDigest(get(commandDigest))
            .setInputRootDigest(get(inputRootDigest))
            // no timeout
            // no doNotCache
            .build());
  }

  /** Wrapped Grpc OutputFile. */
  @Override
  public SymlinkNode newSymlinkNode(String name, Path target) {
    return new GrpcSymlinkNode(
        build.bazel.remote.execution.v2.SymlinkNode.newBuilder()
            .setName(name)
            .setTarget(target.toString())
            .build());
  }

  @Override
  public OutputDirectory newOutputDirectory(Path output, Digest treeDigest) {
    return new GrpcOutputDirectory(
        build.bazel.remote.execution.v2.OutputDirectory.newBuilder()
            .setTreeDigest(get(treeDigest))
            .setPath(output.toString())
            .build());
  }

  @Override
  public Tree newTree(Directory directory, List<Directory> directories) {
    return new GrpcTree(
        build.bazel.remote.execution.v2.Tree.newBuilder()
            .setRoot(get(directory))
            .addAllChildren(
                directories.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .build());
  }

  @Override
  public DirectoryNode newDirectoryNode(String name, Digest digest) {
    return new GrpcDirectoryNode(
        build.bazel.remote.execution.v2.DirectoryNode.newBuilder()
            .setDigest(get(digest))
            .setName(name)
            .build());
  }

  @Override
  public Directory newDirectory(
      List<DirectoryNode> children, List<FileNode> files, List<SymlinkNode> symlinks) {
    return new GrpcDirectory(
        build.bazel.remote.execution.v2.Directory.newBuilder()
            .addAllFiles(files.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .addAllDirectories(
                children.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .addAllSymlinks(symlinks.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .build());
  }

  @Override
  public Digest newDigest(String hash, int size) {
    return new GrpcDigest(
        build.bazel.remote.execution.v2.Digest.newBuilder()
            .setHash(hash)
            .setSizeBytes(size)
            .build());
  }

  @Override
  public OutputFile newOutputFile(
      Path output,
      Digest digest,
      boolean isExecutable,
      ThrowingSupplier<InputStream, IOException> dataSupplier)
      throws IOException {
    Builder builder = build.bazel.remote.execution.v2.OutputFile.newBuilder();
    builder.setPath(output.toString());
    builder.setDigest(get(digest));
    builder.setIsExecutable(isExecutable);
    return new GrpcOutputFile(builder.build());
  }

  @Override
  public FileNode newFileNode(Digest digest, String name, boolean isExecutable) {
    return new GrpcFileNode(
        build.bazel.remote.execution.v2.FileNode.newBuilder()
            .setDigest(get(digest))
            .setName(name)
            .setIsExecutable(isExecutable)
            .build());
  }

  @Override
  public byte[] toByteArray(Directory directory) {
    return get(directory).toByteArray();
  }

  @Override
  public byte[] toByteArray(Tree tree) {
    return get(tree).toByteArray();
  }

  @Override
  public byte[] toByteArray(Command actionCommand) {
    return get(actionCommand).toByteArray();
  }

  @Override
  public byte[] toByteArray(Action action) {
    return get(action).toByteArray();
  }

  @Override
  public Digest computeDigest(byte[] data) {
    return new GrpcDigest(
        build.bazel.remote.execution.v2.Digest.newBuilder()
            .setSizeBytes(data.length)
            .setHash(HASHER.hashBytes(data).toString())
            .build());
  }

  @Override
  public HashFunction getHashFunction() {
    return HASHER;
  }

  private static build.bazel.remote.execution.v2.DirectoryNode get(DirectoryNode directoryNode) {
    return ((GrpcDirectoryNode) directoryNode).directoryNode;
  }

  private static build.bazel.remote.execution.v2.SymlinkNode get(SymlinkNode node) {
    return ((GrpcSymlinkNode) node).node;
  }

  private static build.bazel.remote.execution.v2.Command get(Command command) {
    return ((GrpcCommand) command).command;
  }

  private static build.bazel.remote.execution.v2.Action get(Action action) {
    return ((GrpcAction) action).action;
  }

  private static build.bazel.remote.execution.v2.FileNode get(FileNode fileNode) {
    return ((GrpcFileNode) fileNode).fileNode;
  }

  public static build.bazel.remote.execution.v2.Digest get(Digest blob) {
    return ((GrpcDigest) blob).digest;
  }

  public static build.bazel.remote.execution.v2.OutputFile get(OutputFile outputFile) {
    return ((GrpcOutputFile) outputFile).outputFile;
  }

  public static build.bazel.remote.execution.v2.OutputDirectory get(
      OutputDirectory outputDirectory) {
    return ((GrpcOutputDirectory) outputDirectory).outputDirectory;
  }

  public static build.bazel.remote.execution.v2.Directory get(Directory directory) {
    return ((GrpcDirectory) directory).directory;
  }

  private build.bazel.remote.execution.v2.Tree get(Tree tree) {
    return ((GrpcTree) tree).tree;
  }
}
