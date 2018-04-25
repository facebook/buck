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

import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.devtools.remoteexecution.v1test.Command.EnvironmentVariable;
import com.google.devtools.remoteexecution.v1test.OutputFile.Builder;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** A Grpc-based Protocol implementation. */
public class GrpcProtocol implements Protocol {

  private static final int MAX_INLINED_OUTPUT_FILE_SIZE = 64 * 1024;
  private static final HashFunction HASHER = Hashing.sha1();

  /** Wrapped Grpc Digest. */
  static class GrpcDigest implements Digest {
    private final com.google.devtools.remoteexecution.v1test.Digest digest;

    public GrpcDigest(com.google.devtools.remoteexecution.v1test.Digest digest) {
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
    private final com.google.devtools.remoteexecution.v1test.Command command;

    GrpcCommand(com.google.devtools.remoteexecution.v1test.Command command) {
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
  }

  private static class GrpcFileNode implements FileNode {
    private final com.google.devtools.remoteexecution.v1test.FileNode fileNode;

    public GrpcFileNode(com.google.devtools.remoteexecution.v1test.FileNode fileNode) {
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
    private final com.google.devtools.remoteexecution.v1test.Directory directory;

    public GrpcDirectory(com.google.devtools.remoteexecution.v1test.Directory directory) {
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
  }

  private static class GrpcDirectoryNode implements DirectoryNode {
    private final com.google.devtools.remoteexecution.v1test.DirectoryNode directoryNode;

    public GrpcDirectoryNode(
        com.google.devtools.remoteexecution.v1test.DirectoryNode directoryNode) {
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
    private final com.google.devtools.remoteexecution.v1test.Tree tree;

    public GrpcTree(com.google.devtools.remoteexecution.v1test.Tree tree) {
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
    private final com.google.devtools.remoteexecution.v1test.OutputDirectory outputDirectory;

    GrpcOutputDirectory(
        com.google.devtools.remoteexecution.v1test.OutputDirectory outputDirectory) {
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
    private final com.google.devtools.remoteexecution.v1test.OutputFile outputFile;

    GrpcOutputFile(com.google.devtools.remoteexecution.v1test.OutputFile outputFile) {
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
    @Nullable
    public ByteBuffer getContent() {
      return outputFile.getContent() == null
          ? null
          : outputFile.getContent().asReadOnlyByteBuffer();
    }

    @Override
    public boolean getIsExecutable() {
      return outputFile.getIsExecutable();
    }
  }

  @Override
  public Command parseCommand(ByteBuffer data) throws IOException {
    return new GrpcCommand(com.google.devtools.remoteexecution.v1test.Command.parseFrom(data));
  }

  @Override
  public Directory parseDirectory(ByteBuffer data) throws IOException {
    return new GrpcDirectory(com.google.devtools.remoteexecution.v1test.Directory.parseFrom(data));
  }

  @Override
  public Tree parseTree(ByteBuffer data) throws IOException {
    return new GrpcTree(com.google.devtools.remoteexecution.v1test.Tree.parseFrom(data));
  }

  @Override
  public Digest computeDigest(Directory directory) throws IOException {
    return computeDigest(toByteArray(directory));
  }

  @Override
  public Command newCommand(
      ImmutableList<String> command, ImmutableSortedMap<String, String> commandEnvironment) {
    return new GrpcCommand(
        com.google.devtools.remoteexecution.v1test.Command.newBuilder()
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
            .build());
  }

  @Override
  public OutputDirectory newOutputDirectory(Path output, Digest digest, Digest treeDigest) {
    return new GrpcOutputDirectory(
        com.google.devtools.remoteexecution.v1test.OutputDirectory.newBuilder()
            .setDigest(get(digest))
            .setTreeDigest(get(treeDigest))
            .setPath(output.toString())
            .build());
  }

  @Override
  public Tree newTree(Directory directory, List<Directory> directories) {
    return new GrpcTree(
        com.google.devtools.remoteexecution.v1test.Tree.newBuilder()
            .setRoot(get(directory))
            .addAllChildren(
                directories.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .build());
  }

  @Override
  public DirectoryNode newDirectoryNode(String name, Digest digest) {
    return new GrpcDirectoryNode(
        com.google.devtools.remoteexecution.v1test.DirectoryNode.newBuilder()
            .setDigest(get(digest))
            .setName(name)
            .build());
  }

  @Override
  public Directory newDirectory(List<DirectoryNode> children, List<FileNode> files) {
    return new GrpcDirectory(
        com.google.devtools.remoteexecution.v1test.Directory.newBuilder()
            .addAllFiles(files.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .addAllDirectories(
                children.stream().map(GrpcProtocol::get).collect(Collectors.toList()))
            .build());
  }

  @Override
  public Digest newDigest(String hash, int size) {
    return new GrpcDigest(
        com.google.devtools.remoteexecution.v1test.Digest.newBuilder()
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
    Builder builder = com.google.devtools.remoteexecution.v1test.OutputFile.newBuilder();
    builder.setPath(output.toString());
    builder.setDigest(get(digest));
    builder.setIsExecutable(isExecutable);
    if (digest.getSize() < MAX_INLINED_OUTPUT_FILE_SIZE) {
      try (InputStream dataStream = dataSupplier.get()) {
        builder.setContent(ByteString.readFrom(dataStream));
      }
    }
    return new GrpcOutputFile(builder.build());
  }

  @Override
  public FileNode newFileNode(Digest digest, String name, boolean isExecutable) {
    return new GrpcFileNode(
        com.google.devtools.remoteexecution.v1test.FileNode.newBuilder()
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
  public Digest computeDigest(byte[] data) {
    return new GrpcDigest(
        com.google.devtools.remoteexecution.v1test.Digest.newBuilder()
            .setSizeBytes(data.length)
            .setHash(HASHER.hashBytes(data).toString())
            .build());
  }

  private static com.google.devtools.remoteexecution.v1test.DirectoryNode get(
      DirectoryNode directoryNode) {
    return ((GrpcDirectoryNode) directoryNode).directoryNode;
  }

  private static com.google.devtools.remoteexecution.v1test.Command get(Command command) {
    return ((GrpcCommand) command).command;
  }

  private static com.google.devtools.remoteexecution.v1test.FileNode get(FileNode fileNode) {
    return ((GrpcFileNode) fileNode).fileNode;
  }

  public static com.google.devtools.remoteexecution.v1test.Digest get(Digest blob) {
    return ((GrpcDigest) blob).digest;
  }

  public static com.google.devtools.remoteexecution.v1test.OutputFile get(OutputFile outputFile) {
    return ((GrpcOutputFile) outputFile).outputFile;
  }

  public static com.google.devtools.remoteexecution.v1test.OutputDirectory get(
      OutputDirectory outputDirectory) {
    return ((GrpcOutputDirectory) outputDirectory).outputDirectory;
  }

  public static com.google.devtools.remoteexecution.v1test.Directory get(Directory directory) {
    return ((GrpcDirectory) directory).directory;
  }

  private com.google.devtools.remoteexecution.v1test.Tree get(Tree tree) {
    return ((GrpcTree) tree).tree;
  }
}
