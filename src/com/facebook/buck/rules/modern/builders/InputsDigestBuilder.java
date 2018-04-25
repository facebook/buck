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

import com.facebook.buck.rules.modern.builders.Protocol.Digest;
import com.facebook.buck.rules.modern.builders.Protocol.Directory;
import com.facebook.buck.rules.modern.builders.Protocol.DirectoryNode;
import com.facebook.buck.rules.modern.builders.Protocol.FileNode;
import com.facebook.buck.rules.modern.builders.Protocol.Tree;
import com.facebook.buck.util.filesystem.PathFragments;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.immutables.value.Value;

/** Helper for constructing an input Digest for remote execution. */
class InputsDigestBuilder {
  public static final HashFunction DEFAULT_HASHER = Hashing.sipHash24();

  private final Map<String, FileNode> files = new HashMap<>();
  // The subdirectory builders will all share the root dataMap.
  private final Map<String, InputsDigestBuilder> directories = new HashMap<>();
  private final Map<Digest, ThrowingSupplier<InputStream, IOException>> dataMap;

  private final Delegate delegate;
  private final Protocol protocol;

  InputsDigestBuilder(Delegate delegate, Protocol protocol) {
    this(delegate, new HashMap<>(), protocol);
  }

  private InputsDigestBuilder(
      Delegate delegate,
      Map<Digest, ThrowingSupplier<InputStream, IOException>> dataMap,
      Protocol protocol) {
    this.delegate = delegate;
    this.dataMap = dataMap;
    this.protocol = protocol;
  }

  public static InputsDigestBuilder createDefault(
      Path rootPath, ThrowingFunction<Path, HashCode, IOException> fileHasher) {
    Protocol protocol = new ThriftProtocol();
    return new InputsDigestBuilder(new DefaultDelegate(rootPath, fileHasher, protocol), protocol);
  }

  /**
   * Holder for a digest and a supplier for the data. In some cases, the data itself will never be
   * needed.
   */
  static class DigestAndData {
    final Digest digest;
    final ThrowingSupplier<InputStream, IOException> dataSupplier;

    DigestAndData(Digest digest, ThrowingSupplier<InputStream, IOException> dataSupplier) {
      this.digest = digest;
      this.dataSupplier = dataSupplier;
    }
  }

  /** The delegate is required to provide the digests for data. */
  interface Delegate {
    DigestAndData digest(byte[] data);

    DigestAndData digest(Path path) throws IOException;
  }

  /**
   * The default delegate uses a FileHashLoader for computing file digests and siphash24 for
   * byte[]/directory digests.
   */
  public static class DefaultDelegate implements Delegate {
    private final HashFunction hashFunction;
    private final Path rootPath;
    private final ThrowingFunction<Path, HashCode, IOException> fileHasher;
    private final Protocol protocol;

    public DefaultDelegate(
        Path rootPath,
        ThrowingFunction<Path, HashCode, IOException> fileHasher,
        Protocol protocol) {
      this.rootPath = rootPath;
      this.fileHasher = fileHasher;
      this.protocol = protocol;
      this.hashFunction = DEFAULT_HASHER;
    }

    @Override
    public DigestAndData digest(byte[] data) {
      Digest digest = protocol.newDigest(hashFunction.hashBytes(data).toString(), data.length);
      return new DigestAndData(digest, () -> new ByteArrayInputStream(data));
    }

    @Override
    public DigestAndData digest(Path path) throws IOException {
      Preconditions.checkState(!path.isAbsolute());
      Path resolved = rootPath.resolve(path);
      Digest digest =
          protocol.newDigest(fileHasher.apply(resolved).toString(), (int) Files.size(resolved));
      return new DigestAndData(digest, () -> Files.newInputStream(resolved));
    }
  }

  /** The computed inputs digest along with a map of all the data referenced from the root. */
  @BuckStyleTuple
  @Value.Immutable
  interface AbstractInputs {
    Digest getRootDigest();

    Digest getTreeDigest();

    ImmutableMap<Digest, ThrowingSupplier<InputStream, IOException>> getRequiredData();
  }

  /** Adds a file to the inputs. */
  public void addFile(Path path, boolean isExecutable) {
    Preconditions.checkState(!path.isAbsolute());
    addFileImpl(PathFragments.pathToFragment(path), () -> delegate.digest(path), isExecutable);
  }

  /**
   * Adds a file to the inputs. This can be used to add data when a file doesn't actually exist in
   * the normal build root.
   */
  public void addFile(Path path, Supplier<byte[]> dataSupplier, boolean isExecutable) {
    addFileImpl(
        PathFragments.pathToFragment(path),
        () -> delegate.digest(dataSupplier.get()),
        isExecutable);
  }

  /** Returns the constructed digest and required inputs. */
  public Inputs build() throws IOException {
    Tree tree = buildTree();

    byte[] rootData = protocol.toByteArray(tree.getRoot());
    Digest rootDigest = protocol.computeDigest(rootData);

    byte[] treeData = protocol.toByteArray(tree);
    Digest treeDigest = protocol.computeDigest(treeData);

    return Inputs.of(
        register(new DigestAndData(rootDigest, () -> new ByteArrayInputStream(rootData))),
        register(new DigestAndData(treeDigest, () -> new ByteArrayInputStream(treeData))),
        dataMap);
  }

  private Tree buildTree() throws IOException {
    ImmutableList.Builder<Directory> children = ImmutableList.builder();
    Directory root = buildDigest(children::add);
    return protocol.newTree(root, children.build());
  }

  private Directory buildDigest(Consumer<Directory> child) throws IOException {
    List<FileNode> fileNodes =
        files
            .values()
            .stream()
            .sorted(Comparator.comparing(FileNode::getName))
            .collect(ImmutableList.toImmutableList());

    ImmutableList.Builder<DirectoryNode> childrenBuilder = ImmutableList.builder();
    for (Entry<String, InputsDigestBuilder> entry : directories.entrySet()) {
      Directory childDirectory = entry.getValue().buildDigest(child);
      child.accept(childDirectory);
      byte[] data = protocol.toByteArray(childDirectory);
      Digest digest = protocol.computeDigest(data);
      childrenBuilder.add(
          protocol.newDirectoryNode(
              entry.getKey(),
              register(new DigestAndData(digest, () -> new ByteArrayInputStream(data)))));
    }

    return protocol.newDirectory(childrenBuilder.build(), fileNodes);
  }

  private Digest register(DigestAndData data) {
    dataMap.put(data.digest, data.dataSupplier);
    return data.digest;
  }

  private void addFileImpl(
      PathFragment pathFragment,
      ThrowingSupplier<DigestAndData, IOException> digestSupplier,
      boolean isExecutable) {
    Preconditions.checkState(pathFragment.segmentCount() > 0);
    String name = pathFragment.getSegment(0);

    if (pathFragment.segmentCount() > 1) {
      getDirectory(name)
          .addFileImpl(
              pathFragment.subFragment(1, pathFragment.segmentCount()),
              digestSupplier,
              isExecutable);
    } else {
      Preconditions.checkState(!directories.containsKey(name));
      files.computeIfAbsent(
          name,
          ignored ->
              protocol.newFileNode(
                  register(digestSupplier.asSupplier().get()), name, isExecutable));
    }
  }

  private InputsDigestBuilder getDirectory(String segment) {
    Preconditions.checkState(!files.containsKey(segment));
    return directories.computeIfAbsent(
        segment, ignored -> new InputsDigestBuilder(delegate, dataMap, protocol));
  }
}
