/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.FileNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Tree;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.MerkleTreeNode;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A simple helper to collect remote execution outputs. Given a list of root output
 * files/directories, it will construct all the {@link OutputFile} and {@link OutputDirectory}
 * objects for the outputs offered by the delegate. It will also include a set of {@link
 * UploadDataSupplier} for all the files and serialized data structures that are needed to expand
 * the outputs on the client.
 */
public class OutputsCollector {
  private final Protocol protocol;
  private final Delegate delegate;

  /** The collected outputs-related information. */
  public static class CollectedOutputs {
    public final ImmutableList<OutputFile> outputFiles;
    public final ImmutableList<OutputDirectory> outputDirectories;
    public final ImmutableSet<UploadDataSupplier> requiredData;

    public CollectedOutputs(
        ImmutableList<OutputFile> outputFiles,
        ImmutableList<OutputDirectory> outputDirectories,
        ImmutableSet<UploadDataSupplier> requiredData) {
      this.outputFiles = outputFiles;
      this.outputDirectories = outputDirectories;
      this.requiredData = requiredData;
    }
  }

  /**
   * Delegate to handle filesystem operations. For real cases, {@link FilesystemBackedDelegate} is
   * probably the correct thing to use.
   */
  interface Delegate {

    /** Returns a {@link ByteSource} for the file. */
    ByteSource asByteSource(Path file);

    /** Returns whether the path exists. */
    boolean exists(Path path);

    /** Returns whether the path exists as a directory. */
    boolean isDirectory(Path path);

    /** See {@link Files#walk}. */
    Stream<Path> walk(Path path) throws IOException;

    /** Returns whether the path exists as a regular file. */
    boolean isRegularFile(Path entry);

    /** Returns whether the path is executable. */
    boolean isExecutable(Path entry);

    /** Returns an {@link InputStream} for the path. */
    InputStream getInputStream(Path path) throws IOException;

    /** Returns the size of the file. */
    long size(Path path) throws IOException;
  }

  /** A simple delegate that returns information from the filesystem. */
  public static class FilesystemBackedDelegate implements Delegate {
    @Override
    public ByteSource asByteSource(Path file) {
      return MoreFiles.asByteSource(file);
    }

    @Override
    public boolean exists(Path path) {
      return Files.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
      return Files.isDirectory(path);
    }

    @Override
    public Stream<Path> walk(Path path) throws IOException {
      return Files.walk(path);
    }

    @Override
    public boolean isRegularFile(Path entry) {
      return Files.isRegularFile(entry);
    }

    @Override
    public boolean isExecutable(Path entry) {
      return Files.isExecutable(entry);
    }

    @Override
    public InputStream getInputStream(Path path) throws IOException {
      return new FileInputStream(path.toFile());
    }

    @Override
    public long size(Path path) throws IOException {
      return Files.size(path);
    }
  }

  public OutputsCollector(Protocol protocol, Delegate delegate) {
    this.protocol = protocol;
    this.delegate = delegate;
  }

  /** Returns the collected output information. */
  public CollectedOutputs collect(Set<Path> outputs, Path buildDir) throws IOException {
    ImmutableList.Builder<OutputFile> outputFilesBuilder = ImmutableList.builder();
    ImmutableList.Builder<OutputDirectory> outputDirectoriesBuilder = ImmutableList.builder();
    Set<UploadDataSupplier> requiredDataBuilder = new HashSet<>();
    for (Path output : outputs) {
      Path path = buildDir.resolve(output);
      Preconditions.checkState(delegate.exists(path), "Required path %s doesn't exist.", path);
      if (delegate.isDirectory(path)) {
        Map<Path, FileNode> files = new HashMap<>();

        try (Stream<Path> contents = delegate.walk(path)) {
          RichStream.from(contents)
              .forEachThrowing(
                  entry -> {
                    if (delegate.isRegularFile(entry)) {
                      files.put(
                          path.relativize(entry),
                          protocol.newFileNode(
                              protocol.newDigest(
                                  hashFile(entry).toString(), (int) delegate.size(entry)),
                              entry.getFileName().toString(),
                              delegate.isExecutable(entry)));
                    }
                  });
        }

        MerkleTreeNodeCache merkleTreeCache = new MerkleTreeNodeCache(protocol);

        MerkleTreeNode node = merkleTreeCache.createNode(files, ImmutableMap.of());

        node.forAllFiles(
            (file, fileNode) ->
                requiredDataBuilder.add(
                    UploadDataSupplier.of(
                        fileNode.getDigest(), () -> delegate.getInputStream(path.resolve(file)))));

        List<Directory> directories = new ArrayList<>();
        merkleTreeCache.forAllData(node, data -> directories.add(data.getDirectory()));
        Preconditions.checkState(!directories.isEmpty());

        Tree tree = protocol.newTree(directories.get(0), directories);
        byte[] treeData = protocol.toByteArray(tree);
        Digest treeDigest = protocol.computeDigest(treeData);

        outputDirectoriesBuilder.add(protocol.newOutputDirectory(output, treeDigest));
        requiredDataBuilder.add(
            UploadDataSupplier.of(treeDigest, () -> new ByteArrayInputStream(treeData)));
      } else {
        long size = delegate.size(path);
        boolean isExecutable = delegate.isExecutable(path);
        Digest digest = protocol.newDigest(hashFile(path).toString(), (int) size);

        UploadDataSupplier dataSupplier =
            UploadDataSupplier.of(digest, () -> delegate.getInputStream(path));
        outputFilesBuilder.add(protocol.newOutputFile(output, digest, isExecutable));
        requiredDataBuilder.add(dataSupplier);
      }
    }
    return new CollectedOutputs(
        outputFilesBuilder.build(),
        outputDirectoriesBuilder.build(),
        ImmutableSet.copyOf(requiredDataBuilder));
  }

  private HashCode hashFile(Path file) throws IOException {
    return delegate.asByteSource(file).hash(protocol.getHashFunction());
  }
}
