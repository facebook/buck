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

package com.facebook.buck.rules.modern.builders.thrift;

import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.remoteexecution.executionengine.EnvironmentVariable;
import com.facebook.remoteexecution.executionengine.Platform;
import com.facebook.thrift.TBase;
import com.facebook.thrift.TDeserializer;
import com.facebook.thrift.TException;
import com.facebook.thrift.TSerializer;
import com.facebook.thrift.protocol.TCompactProtocol;
import com.google.common.base.Preconditions;
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
import java.util.Set;
import java.util.stream.Collectors;

/** A Thrift-based Protocol implementation. */
public class ThriftProtocol implements Protocol {
  // TODO(shivanker): This hash function is only used to generate hashes of data we have in memory.
  // We still use the FileHashCache to compute hashes of source files. So until that is switched
  // over to SHA256, we cannot really change this.
  private static final HashFunction HASHER = Hashing.sha1();
  private static final int ACTION_TIMEOUT_SECS = 600;
  private static final boolean ACTION_DO_NOT_CACHE = false;
  private static final String COMMAND_WORKING_DIRECTORY = "";

  /** Digest mapping */
  public static class ThriftDigest implements Digest {
    private final com.facebook.remoteexecution.cas.Digest digest;

    public ThriftDigest(com.facebook.remoteexecution.cas.Digest digest) {
      Preconditions.checkNotNull(digest);
      Preconditions.checkNotNull(digest.getHash());
      this.digest = digest;
    }

    @Override
    public String getHash() {
      return digest.hash;
    }

    @Override
    public int getSize() {
      return (int) digest.size_bytes;
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
    private final com.facebook.remoteexecution.executionengine.Command command;

    ThriftCommand(com.facebook.remoteexecution.executionengine.Command command) {
      this.command = command;
    }

    @Override
    public ImmutableList<String> getCommand() {
      return ImmutableList.copyOf(command.arguments);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment() {
      return command
          .environment_variables
          .stream()
          .collect(ImmutableMap.toImmutableMap(var -> var.name, var -> var.value));
    }

    @Override
    public ImmutableList<String> getOutputFiles() {
      return ImmutableList.copyOf(command.output_files);
    }

    @Override
    public ImmutableList<String> getOutputDirectories() {
      return ImmutableList.copyOf(command.output_directories);
    }
  }

  private static class ThriftAction implements Action {
    private final com.facebook.remoteexecution.executionengine.Action action;

    ThriftAction(com.facebook.remoteexecution.executionengine.Action action) {
      Preconditions.checkNotNull(action);
      this.action = action;
    }

    @Override
    public Digest getCommandDigest() {
      return new ThriftDigest(action.command_digest);
    }

    @Override
    public Digest getInputRootDigest() {
      return new ThriftDigest(action.input_root_digest);
    }
  }

  private static class ThriftFileNode implements FileNode {
    private final com.facebook.remoteexecution.cas.FileNode fileNode;

    public ThriftFileNode(com.facebook.remoteexecution.cas.FileNode fileNode) {
      Preconditions.checkNotNull(fileNode);
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
      return fileNode.is_executable;
    }
  }

  private static class ThriftDirectory implements Directory {
    private final com.facebook.remoteexecution.cas.Directory directory;

    public ThriftDirectory(com.facebook.remoteexecution.cas.Directory directory) {
      Preconditions.checkNotNull(directory);
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
    private final com.facebook.remoteexecution.cas.DirectoryNode directoryNode;

    public ThriftDirectoryNode(com.facebook.remoteexecution.cas.DirectoryNode directoryNode) {
      Preconditions.checkNotNull(directoryNode);
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

  private void parseStruct(ByteBuffer source, TBase dest) throws IOException {
    try {
      deserialize(source, dest);
    } catch (ThriftException e) {
      throw new IOException(e);
    }
  }

  private static class ThriftTree implements Tree {
    private final com.facebook.remoteexecution.executionengine.Tree tree;

    public ThriftTree(com.facebook.remoteexecution.executionengine.Tree tree) {
      Preconditions.checkNotNull(tree);
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

  /** OutputDirectory mapping */
  public static class ThriftOutputDirectory implements OutputDirectory {
    private final com.facebook.remoteexecution.executionengine.OutputDirectory outputDirectory;

    public ThriftOutputDirectory(
        com.facebook.remoteexecution.executionengine.OutputDirectory outputDirectory) {
      Preconditions.checkNotNull(outputDirectory);
      this.outputDirectory = outputDirectory;
    }

    @Override
    public String getPath() {
      return outputDirectory.path;
    }

    @Override
    public Digest getTreeDigest() {
      return new ThriftDigest(outputDirectory.tree_digest);
    }
  }

  @Override
  public SymlinkNode newSymlinkNode(String name, Path path) {
    return new ThriftSymlinkNode(
        new com.facebook.remoteexecution.cas.SymlinkNode(
            name, path.toString(), path.toFile().isDirectory()));
  }

  @Override
  public Command newCommand(
      ImmutableList<String> command,
      ImmutableSortedMap<String, String> commandEnvironment,
      Set<Path> outputs) {
    List<String> outputFiles =
        outputs.stream().map(Path::toString).sorted().collect(Collectors.toList());

    return new ThriftCommand(
        new com.facebook.remoteexecution.executionengine.Command(
            command,
            commandEnvironment
                .entrySet()
                .stream()
                .map(entry -> new EnvironmentVariable(entry.getKey(), entry.getValue()))
                .collect(ImmutableList.toImmutableList()),
            outputFiles,
            outputFiles,
            new Platform(),
            COMMAND_WORKING_DIRECTORY));
  }

  @Override
  public Action newAction(Digest commandDigest, Digest inputRootDigest) {
    return new ThriftAction(
        new com.facebook.remoteexecution.executionengine.Action(
            get(commandDigest), get(inputRootDigest), ACTION_TIMEOUT_SECS, ACTION_DO_NOT_CACHE));
  }

  @Override
  public OutputDirectory newOutputDirectory(Path output, Digest treeDigest) {
    return new ThriftOutputDirectory(
        new com.facebook.remoteexecution.executionengine.OutputDirectory(
            output.toString(), get(treeDigest)));
  }

  @Override
  public Tree newTree(Directory directory, List<Directory> directories) {
    return new ThriftTree(
        new com.facebook.remoteexecution.executionengine.Tree(
            get(directory),
            directories.stream().map(ThriftProtocol::get).collect(Collectors.toList())));
  }

  private static com.facebook.remoteexecution.cas.Directory get(Directory directory) {
    Preconditions.checkNotNull(directory);
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

  @Override
  public byte[] toByteArray(Action action) {
    return serialize(get(action));
  }

  private com.facebook.remoteexecution.executionengine.Command get(Command command) {
    Preconditions.checkNotNull(command);
    return ((ThriftCommand) command).command;
  }

  private com.facebook.remoteexecution.executionengine.Action get(Action action) {
    Preconditions.checkNotNull(action);
    return ((ThriftAction) action).action;
  }

  private com.facebook.remoteexecution.executionengine.Tree get(Tree tree) {
    Preconditions.checkNotNull(tree);
    return ((ThriftTree) tree).tree;
  }

  @Override
  public Digest newDigest(String hash, int size) {
    return new ThriftDigest(new com.facebook.remoteexecution.cas.Digest(hash, size));
  }

  @Override
  public OutputFile newOutputFile(
      Path output,
      Digest digest,
      boolean isExecutable,
      ThrowingSupplier<InputStream, IOException> dataSupplier)
      throws IOException {
    return new ThriftOutputFile(
        new com.facebook.remoteexecution.executionengine.OutputFile(
            output.toString(), get(digest), isExecutable));
  }

  private static class ThriftSymlinkNode implements SymlinkNode {
    private final com.facebook.remoteexecution.cas.SymlinkNode symlink;

    private ThriftSymlinkNode(com.facebook.remoteexecution.cas.SymlinkNode symlink) {
      Preconditions.checkNotNull(symlink);
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

  /** OutputFile mapping */
  public static class ThriftOutputFile implements OutputFile {
    private final com.facebook.remoteexecution.executionengine.OutputFile outputFile;

    public ThriftOutputFile(com.facebook.remoteexecution.executionengine.OutputFile outputFile) {
      Preconditions.checkNotNull(outputFile);
      this.outputFile = outputFile;
    }

    @Override
    public String getPath() {
      return outputFile.path;
    }

    @Override
    public Digest getDigest() {
      return new ThriftDigest(outputFile.data_digest);
    }

    @Override
    public boolean getIsExecutable() {
      return outputFile.is_executable;
    }
  }

  @Override
  public FileNode newFileNode(Digest digest, String name, boolean isExecutable) {
    return new ThriftFileNode(
        new com.facebook.remoteexecution.cas.FileNode(name, get(digest), isExecutable));
  }

  public static com.facebook.remoteexecution.cas.Digest get(Digest digest) {
    Preconditions.checkNotNull(digest.getHash());
    return ((ThriftDigest) digest).digest;
  }

  private static com.facebook.remoteexecution.cas.DirectoryNode get(DirectoryNode directoryNode) {
    Preconditions.checkNotNull(directoryNode);
    return ((ThriftDirectoryNode) directoryNode).directoryNode;
  }

  private static com.facebook.remoteexecution.cas.SymlinkNode get(SymlinkNode symlink) {
    Preconditions.checkNotNull(symlink);
    return ((ThriftSymlinkNode) symlink).symlink;
  }

  private static com.facebook.remoteexecution.cas.FileNode get(FileNode fileNode) {
    Preconditions.checkNotNull(fileNode);
    return ((ThriftFileNode) fileNode).fileNode;
  }

  @Override
  public Command parseCommand(ByteBuffer data) throws IOException {
    com.facebook.remoteexecution.executionengine.Command command =
        new com.facebook.remoteexecution.executionengine.Command();
    parseStruct(data, command);
    return new ThriftCommand(command);
  }

  @Override
  public Action parseAction(ByteBuffer data) throws IOException {
    com.facebook.remoteexecution.executionengine.Action action =
        new com.facebook.remoteexecution.executionengine.Action();
    parseStruct(data, action);
    return new ThriftAction(action);
  }

  @Override
  public Directory parseDirectory(ByteBuffer data) throws IOException {
    com.facebook.remoteexecution.cas.Directory directory =
        new com.facebook.remoteexecution.cas.Directory();
    parseStruct(data, directory);
    return new ThriftDirectory(directory);
  }

  @Override
  public Tree parseTree(ByteBuffer data) throws IOException {
    com.facebook.remoteexecution.executionengine.Tree tree =
        new com.facebook.remoteexecution.executionengine.Tree();
    parseStruct(data, tree);
    return new ThriftTree(tree);
  }

  @Override
  public Digest computeDigest(Directory directory) throws IOException {
    return computeDigest(serialize(((ThriftDirectory) directory).directory));
  }

  @Override
  public DirectoryNode newDirectoryNode(String name, Digest child) {
    return new ThriftDirectoryNode(
        new com.facebook.remoteexecution.cas.DirectoryNode(name, get(child)));
  }

  @Override
  public Directory newDirectory(
      List<DirectoryNode> children, List<FileNode> files, List<SymlinkNode> symlinks) {
    return new ThriftDirectory(
        new com.facebook.remoteexecution.cas.Directory(
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
        new com.facebook.remoteexecution.cas.Digest(
            HASHER.hashBytes(data).toString(), data.length));
  }

  @Override
  public HashFunction getHashFunction() {
    return HASHER;
  }

  private static byte[] serialize(TBase source) {
    TSerializer deserializer = new TSerializer(new TCompactProtocol.Factory());
    try {
      return deserializer.serialize(source);
    } catch (TException e) {
      throw new RuntimeException(e);
    }
  }

  private static void deserialize(ByteBuffer source, TBase dest) throws ThriftException {
    TDeserializer deserializer = new TDeserializer(new TCompactProtocol.Factory());
    try {
      deserializer.deserialize(dest, source.array());
    } catch (TException e) {
      throw new ThriftException(e);
    }
  }
}
