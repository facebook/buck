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

import com.facebook.buck.util.filesystem.PathFragments;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Helper for constructing an input Digest for remote execution. */
public class FileTreeBuilder {

  /**
   * Represents a single input file. FileTreeBuilder will construct merkle trees containing all the
   * specified InputFiles.
   */
  static class InputFile {
    final String hash;
    final int size;
    final boolean isExecutable;
    final ThrowingSupplier<InputStream, IOException> dataSupplier;

    InputFile(
        String hash,
        int size,
        boolean isExecutable,
        ThrowingSupplier<InputStream, IOException> dataSupplier) {
      this.hash = hash;
      this.size = size;
      this.isExecutable = isExecutable;
      this.dataSupplier = dataSupplier;
    }
  }

  private static class WrappedIOException extends RuntimeException {
    WrappedIOException(IOException e) {
      super(e);
    }

    @Override
    public synchronized IOException getCause() {
      return (IOException) super.getCause();
    }
  }

  private static class DirectoryBuilder {
    private final Map<String, DirectoryBuilder> children = new HashMap<>();
    private final Map<String, InputFile> files = new HashMap<>();

    private void addFileImpl(
        PathFragment pathFragment, ThrowingSupplier<InputFile, IOException> dataSupplier)
        throws IOException {
      Preconditions.checkState(pathFragment.segmentCount() > 0);
      String name = pathFragment.getSegment(0);

      if (pathFragment.segmentCount() > 1) {
        getDirectory(name)
            .addFileImpl(pathFragment.subFragment(1, pathFragment.segmentCount()), dataSupplier);
      } else {
        Preconditions.checkState(!children.containsKey(name));
        try {
          files.computeIfAbsent(
              name,
              ignored -> {
                try {
                  return dataSupplier.get();
                } catch (IOException e) {
                  throw new WrappedIOException(e);
                }
              });
        } catch (WrappedIOException e) {
          throw e.getCause();
        }
      }
    }

    private DirectoryBuilder getDirectory(String segment) {
      Preconditions.checkState(!files.containsKey(segment));
      return children.computeIfAbsent(segment, ignored -> new DirectoryBuilder());
    }
  }

  private final DirectoryBuilder root = new DirectoryBuilder();

  FileTreeBuilder() {}

  /** Adds a file to the inputs. */
  public void addFile(Path path, ThrowingSupplier<InputFile, IOException> dataSupplier)
      throws IOException {
    Preconditions.checkState(!path.isAbsolute());
    root.addFileImpl(PathFragments.pathToFragment(path), dataSupplier);
  }

  /**
   * Adds a file to the inputs. This can be used to add data when a file doesn't actually exist in
   * the normal build root.
   */
  public void addFile(
      Path path,
      Supplier<byte[]> dataSupplier,
      Function<byte[], String> dataHasher,
      boolean isExecutable)
      throws IOException {
    root.addFileImpl(
        PathFragments.pathToFragment(path),
        () -> {
          byte[] data = dataSupplier.get();
          return new InputFile(
              dataHasher.apply(data),
              data.length,
              isExecutable,
              () -> new ByteArrayInputStream(data));
        });
  }

  /**
   * Can be used to compute data from the FileTreeBuilder. This will basically do a bottom-up walk
   * of the added input files (and their directories).
   */
  interface TreeBuilder<T> {
    TreeBuilder<T> addDirectory(String name);

    void addFile(
        String name,
        String hash,
        int size,
        boolean isExecutable,
        ThrowingSupplier<InputStream, IOException> dataSupplier);

    T build();
  }

  /** This can be used to create the merkle tree of the added files. */
  public static class ProtocolTreeBuilder implements TreeBuilder<Protocol.Digest> {
    private final BiConsumer<Protocol.Digest, ThrowingSupplier<InputStream, IOException>>
        requiredDataConsumer;
    private final Consumer<Protocol.Directory> directoryConsumer;
    private final Protocol protocol;

    private final ImmutableList.Builder<Protocol.DirectoryNode> children = ImmutableList.builder();
    private final ImmutableList.Builder<Protocol.FileNode> files = ImmutableList.builder();

    public ProtocolTreeBuilder(
        BiConsumer<Protocol.Digest, ThrowingSupplier<InputStream, IOException>>
            requiredDataConsumer,
        Consumer<Protocol.Directory> directoryConsumer,
        Protocol protocol) {
      this.requiredDataConsumer = requiredDataConsumer;
      this.directoryConsumer = directoryConsumer;
      this.protocol = protocol;
    }

    @Override
    public TreeBuilder<Protocol.Digest> addDirectory(String name) {
      return new ProtocolTreeBuilder(requiredDataConsumer, directoryConsumer, protocol) {
        @Override
        public Protocol.Digest build() {
          Protocol.Digest child = super.build();
          children.add(protocol.newDirectoryNode(name, child));
          return child;
        }
      };
    }

    @Override
    public void addFile(
        String name,
        String hash,
        int size,
        boolean isExecutable,
        ThrowingSupplier<InputStream, IOException> dataSupplier) {
      Protocol.Digest digest = protocol.newDigest(hash, size);
      files.add(protocol.newFileNode(digest, name, isExecutable));
      requiredDataConsumer.accept(digest, dataSupplier);
    }

    @Override
    public Protocol.Digest build() {
      Protocol.Directory directory =
          protocol.newDirectory(
              children
                  .build()
                  .stream()
                  .sorted(Comparator.comparing(Protocol.DirectoryNode::getName))
                  .collect(Collectors.toList()),
              files
                  .build()
                  .stream()
                  .sorted(Comparator.comparing(Protocol.FileNode::getName))
                  .collect(Collectors.toList()));
      byte[] data = protocol.toByteArray(directory);
      Protocol.Digest digest = protocol.computeDigest(data);
      requiredDataConsumer.accept(digest, () -> new ByteArrayInputStream(data));
      directoryConsumer.accept(directory);
      return digest;
    }
  }

  public <T> T buildTree(TreeBuilder<T> builder) {
    return buildTree(root, builder);
  }

  private <T> T buildTree(DirectoryBuilder root, TreeBuilder<T> builder) {
    root.children.forEach((name, directory) -> buildTree(directory, builder.addDirectory(name)));
    root.files.forEach(
        (name, file) ->
            builder.addFile(name, file.hash, file.size, file.isExecutable, file.dataSupplier));
    return builder.build();
  }
}
