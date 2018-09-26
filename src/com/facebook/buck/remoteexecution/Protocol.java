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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashFunction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The Protocol interface is used by many parts of isolated/remote execution and abstracts away the
 * underlying serialization protocol and in-memory representation.
 */
public interface Protocol {
  /** A content digest. */
  interface Digest {
    String getHash();

    int getSize();
  }

  /** Represents a possibly executable file in directories/trees. */
  interface FileNode {

    String getName();

    Digest getDigest();

    boolean getIsExecutable();
  }

  /** Representation of a symlink. */
  interface SymlinkNode {
    String getName();

    String getTarget();
  }

  /** Represents a child of a Directory. */
  interface DirectoryNode {

    String getName();

    Digest getDigest();
  }

  /** A Directory consists of a list of files and child DirectoryNodes. */
  interface Directory {

    Iterable<FileNode> getFilesList();

    Iterable<DirectoryNode> getDirectoriesList();

    Iterable<SymlinkNode> getSymlinksList();
  }

  /** A Tree contains all Directories in a merkle tree in a single structure. */
  interface Tree {
    Iterable<Directory> getChildrenList();

    Directory getRoot();
  }

  /** A command line and environment variables. */
  interface Command {

    ImmutableList<String> getCommand();

    ImmutableMap<String, String> getEnvironment();

    ImmutableList<String> getOutputFiles();

    ImmutableList<String> getOutputDirectories();
  }

  /** An action to execute remotely */
  interface Action {

    Digest getCommandDigest();

    Digest getInputRootDigest();
  }

  /** An OutputDirectory is a merkle tree rooted at a particular path. */
  interface OutputDirectory {

    String getPath();

    Digest getTreeDigest();
  }

  /** An OutputFile is like a FileNode for action outputs. */
  interface OutputFile {

    String getPath();

    Digest getDigest();

    boolean getIsExecutable();
  }

  Command newCommand(
      ImmutableList<String> command,
      ImmutableSortedMap<String, String> commandEnvironment,
      Set<Path> outputs);

  Action newAction(Digest commandDigest, Digest inputRootDigest);

  OutputDirectory newOutputDirectory(Path output, Digest treeDigest);

  Tree newTree(Directory directory, List<Directory> directories);

  Digest newDigest(String hash, int size);

  OutputFile newOutputFile(
      Path output,
      Digest digest,
      boolean isExecutable,
      ThrowingSupplier<InputStream, IOException> dataSupplier)
      throws IOException;

  FileNode newFileNode(Digest digest, String name, boolean isExecutable);

  SymlinkNode newSymlinkNode(String name, Path path);

  Command parseCommand(ByteBuffer data) throws IOException;

  Action parseAction(ByteBuffer data) throws IOException;

  Directory parseDirectory(ByteBuffer data) throws IOException;

  Tree parseTree(ByteBuffer data) throws IOException;

  DirectoryNode newDirectoryNode(String name, Digest child);

  Directory newDirectory(
      List<DirectoryNode> children, List<FileNode> files, List<SymlinkNode> symlinks);

  byte[] toByteArray(Directory directory);

  byte[] toByteArray(Tree tree);

  byte[] toByteArray(Command actionCommand);

  byte[] toByteArray(Action action);

  Digest computeDigest(Directory directory) throws IOException;

  Digest computeDigest(byte[] data);

  HashFunction getHashFunction();
}
