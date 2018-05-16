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

import com.facebook.buck.rules.modern.builders.thrift.EnvironmentVariable;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.thrift.TBase;

/** A Thrift-based Protocol implementation. */
public class ThriftProtocol implements Protocol {
  private static final HashFunction HASHER = Hashing.sipHash24();

  private static class ThriftDigest implements Digest {
    private final com.facebook.buck.rules.modern.builders.thrift.Digest digest;

    public ThriftDigest(com.facebook.buck.rules.modern.builders.thrift.Digest digest) {
      this.digest = digest;
    }

    @Override
    public String getHash() {
      return digest.hash;
    }

    @Override
    public int getSize() {
      return (int) digest.size;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ThriftDigest)) {
        return false;
      }
      return ((ThriftDigest) obj).digest.equals(digest);
    }

    @Override
    public int hashCode() {
      return digest.hashCode();
    }
  }

  private static class ThriftCommand implements Command {
    private final com.facebook.buck.rules.modern.builders.thrift.Command command;

    ThriftCommand(com.facebook.buck.rules.modern.builders.thrift.Command command) {
      this.command = command;
    }

    @Override
    public ImmutableList<String> getCommand() {
      return ImmutableList.copyOf(command.arguments);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment() {
      return command
          .environmentVariables
          .stream()
          .collect(ImmutableMap.toImmutableMap(var -> var.name, var -> var.value));
    }
  }

  private static class ThriftFileNode implements FileNode {
    private final com.facebook.buck.rules.modern.builders.thrift.FileNode fileNode;

    public ThriftFileNode(com.facebook.buck.rules.modern.builders.thrift.FileNode fileNode) {
      this.fileNode = fileNode;
    }

    @Override
    public String getName() {
      return fileNode.name;
    }

    @Override
    public Digest getDigest() {
      return new ThriftDigest(fileNode.digest);
    }

    @Override
    public boolean getIsExecutable() {
      return fileNode.isExecutable;
    }
  }

  private static class ThriftDirectory implements Directory {
    private final com.facebook.buck.rules.modern.builders.thrift.Directory directory;

    public ThriftDirectory(com.facebook.buck.rules.modern.builders.thrift.Directory directory) {
      this.directory = directory;
    }

    @Override
    public Iterable<FileNode> getFilesList() {
      return directory.files.stream().map(ThriftFileNode::new).collect(Collectors.toList());
    }

    @Override
    public Iterable<DirectoryNode> getDirectoriesList() {
      return directory
          .directories
          .stream()
          .map(ThriftDirectoryNode::new)
          .collect(Collectors.toList());
    }

    @Override
    public Iterable<SymlinkNode> getSymlinksList() {
      return directory.symlinks.stream().map(ThriftSymlinkNode::new).collect(Collectors.toList());
    }
  }

  private static class ThriftDirectoryNode implements DirectoryNode {
    private final com.facebook.buck.rules.modern.builders.thrift.DirectoryNode directoryNode;

    public ThriftDirectoryNode(
        com.facebook.buck.rules.modern.builders.thrift.DirectoryNode directoryNode) {
      this.directoryNode = directoryNode;
    }

    @Override
    public String getName() {
      return directoryNode.name;
    }

    @Override
    public Digest getDigest() {
      return new ThriftDigest(directoryNode.digest);
    }
  }

  private void parseStruct(ByteBuffer data, TBase<?, ?> command) throws IOException {
    try {
      ThriftUtil.deserialize(com.facebook.buck.slb.ThriftProtocol.COMPACT, data, command);
    } catch (ThriftException e) {
      throw new IOException(e);
    }
  }

  private static class ThriftTree implements Tree {
    private final com.facebook.buck.rules.modern.builders.thrift.Tree tree;

    public ThriftTree(com.facebook.buck.rules.modern.builders.thrift.Tree tree) {
      this.tree = tree;
    }

    @Override
    public Iterable<Directory> getChildrenList() {
      return tree.children.stream().map(ThriftDirectory::new).collect(Collectors.toList());
    }

    @Override
    public Directory getRoot() {
      return new ThriftDirectory(tree.root);
    }
  }

  private static class ThriftOutputDirectory implements OutputDirectory {
    private final com.facebook.buck.rules.modern.builders.thrift.OutputDirectory outputDirectory;

    ThriftOutputDirectory(
        com.facebook.buck.rules.modern.builders.thrift.OutputDirectory outputDirectory) {
      this.outputDirectory = outputDirectory;
    }

    @Override
    public String getPath() {
      return outputDirectory.path;
    }

    @Override
    public Digest getTreeDigest() {
      return new ThriftDigest(outputDirectory.treeDigest);
    }
  }

  @Override
  public SymlinkNode newSymlinkNode(String name, Path path) {
    return new ThriftSymlinkNode(
        new com.facebook.buck.rules.modern.builders.thrift.SymlinkNode(name, path.toString()));
  }

  @Override
  public Command newCommand(
      ImmutableList<String> command, ImmutableSortedMap<String, String> commandEnvironment) {
    return new ThriftCommand(
        new com.facebook.buck.rules.modern.builders.thrift.Command(
            command,
            commandEnvironment
                .entrySet()
                .stream()
                .map(entry -> new EnvironmentVariable(entry.getKey(), entry.getValue()))
                .collect(ImmutableList.toImmutableList())));
  }

  @Override
  public OutputDirectory newOutputDirectory(Path output, Digest digest, Digest treeDigest) {
    return new ThriftOutputDirectory(
        new com.facebook.buck.rules.modern.builders.thrift.OutputDirectory(
            output.toString(), get(treeDigest)));
  }

  @Override
  public Tree newTree(Directory directory, List<Directory> directories) {
    return new ThriftTree(
        new com.facebook.buck.rules.modern.builders.thrift.Tree(
            get(directory),
            directories.stream().map(ThriftProtocol::get).collect(Collectors.toList())));
  }

  private static com.facebook.buck.rules.modern.builders.thrift.Directory get(Directory directory) {
    return ((ThriftDirectory) directory).directory;
  }

  @Override
  public byte[] toByteArray(Tree tree) {
    return serialize(get(tree));
  }

  @Override
  public byte[] toByteArray(Command actionCommand) {
    return serialize(get(actionCommand));
  }

  private com.facebook.buck.rules.modern.builders.thrift.Command get(Command command) {
    return ((ThriftCommand) command).command;
  }

  private com.facebook.buck.rules.modern.builders.thrift.Tree get(Tree tree) {
    return ((ThriftTree) tree).tree;
  }

  @Override
  public Digest newDigest(String hash, int size) {
    return new ThriftDigest(new com.facebook.buck.rules.modern.builders.thrift.Digest(hash, size));
  }

  @Override
  public OutputFile newOutputFile(
      Path output,
      Digest digest,
      boolean isExecutable,
      ThrowingSupplier<InputStream, IOException> dataSupplier)
      throws IOException {
    return new ThriftOutputFile(
        new com.facebook.buck.rules.modern.builders.thrift.OutputFile(
            output.toString(), get(digest), null, isExecutable));
  }

  private static class ThriftSymlinkNode implements SymlinkNode {
    private final com.facebook.buck.rules.modern.builders.thrift.SymlinkNode symlink;

    private ThriftSymlinkNode(com.facebook.buck.rules.modern.builders.thrift.SymlinkNode symlink) {
      this.symlink = symlink;
    }

    @Override
    public String getName() {
      return symlink.getName();
    }

    @Override
    public String getTarget() {
      return symlink.getTarget();
    }
  }

  private static class ThriftOutputFile implements OutputFile {
    private final com.facebook.buck.rules.modern.builders.thrift.OutputFile outputFile;

    ThriftOutputFile(com.facebook.buck.rules.modern.builders.thrift.OutputFile outputFile) {
      this.outputFile = outputFile;
    }

    @Override
    public String getPath() {
      return outputFile.path;
    }

    @Override
    public Digest getDigest() {
      return new ThriftDigest(outputFile.digest);
    }

    @Override
    @Nullable
    public ByteBuffer getContent() {
      return outputFile.content;
    }

    @Override
    public boolean getIsExecutable() {
      return outputFile.isExecutable;
    }
  }

  @Override
  public FileNode newFileNode(Digest digest, String name, boolean isExecutable) {
    return new ThriftFileNode(
        new com.facebook.buck.rules.modern.builders.thrift.FileNode(
            name, get(digest), isExecutable));
  }

  private static com.facebook.buck.rules.modern.builders.thrift.Digest get(Digest digest) {
    return ((ThriftDigest) digest).digest;
  }

  private static com.facebook.buck.rules.modern.builders.thrift.DirectoryNode get(
      DirectoryNode directoryNode) {
    return ((ThriftDirectoryNode) directoryNode).directoryNode;
  }

  private static com.facebook.buck.rules.modern.builders.thrift.SymlinkNode get(
      SymlinkNode symlink) {
    return ((ThriftSymlinkNode) symlink).symlink;
  }

  private static com.facebook.buck.rules.modern.builders.thrift.FileNode get(FileNode fileNode) {
    return ((ThriftFileNode) fileNode).fileNode;
  }

  @Override
  public Command parseCommand(ByteBuffer data) throws IOException {
    com.facebook.buck.rules.modern.builders.thrift.Command command =
        new com.facebook.buck.rules.modern.builders.thrift.Command();
    parseStruct(data, command);
    return new ThriftCommand(command);
  }

  @Override
  public Directory parseDirectory(ByteBuffer data) throws IOException {
    com.facebook.buck.rules.modern.builders.thrift.Directory directory =
        new com.facebook.buck.rules.modern.builders.thrift.Directory();
    parseStruct(data, directory);
    return new ThriftDirectory(directory);
  }

  @Override
  public Tree parseTree(ByteBuffer data) throws IOException {
    com.facebook.buck.rules.modern.builders.thrift.Tree tree =
        new com.facebook.buck.rules.modern.builders.thrift.Tree();
    parseStruct(data, tree);
    return new ThriftTree(tree);
  }

  @Override
  public Digest computeDigest(Directory directory) throws IOException {
    return computeDigest(serialize(((ThriftDirectory) directory).directory));
  }

  private byte[] serialize(TBase<?, ?> struct) {
    try {
      return ThriftUtil.serialize(com.facebook.buck.slb.ThriftProtocol.COMPACT, struct);
    } catch (ThriftException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public DirectoryNode newDirectoryNode(String name, Digest child) {
    return new ThriftDirectoryNode(
        new com.facebook.buck.rules.modern.builders.thrift.DirectoryNode(name, get(child)));
  }

  @Override
  public Directory newDirectory(
      List<DirectoryNode> children, List<FileNode> files, List<SymlinkNode> symlinks) {
    return new ThriftDirectory(
        new com.facebook.buck.rules.modern.builders.thrift.Directory(
            files.stream().map(ThriftProtocol::get).collect(Collectors.toList()),
            children.stream().map(ThriftProtocol::get).collect(Collectors.toList()),
            symlinks.stream().map(ThriftProtocol::get).collect(Collectors.toList())));
  }

  @Override
  public byte[] toByteArray(Directory directory) {

    return serialize(get(directory));
  }

  @Override
  public Digest computeDigest(byte[] data) {
    return new ThriftDigest(
        new com.facebook.buck.rules.modern.builders.thrift.Digest(
            HASHER.hashBytes(data).toString(), data.length));
  }
}
