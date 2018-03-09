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

import com.facebook.buck.rules.modern.builders.thrift.Digest;
import com.facebook.buck.rules.modern.builders.thrift.Directory;
import com.facebook.buck.rules.modern.builders.thrift.DirectoryNode;
import com.facebook.buck.rules.modern.builders.thrift.FileNode;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.util.PathFragments;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/** Helper for constructing an input Digest for remote execution. */
class InputsDigestBuilder {
  private final Map<String, FileNode> files = new HashMap<>();
  // The subdirectory builders will all share the root dataMap.
  private final Map<String, InputsDigestBuilder> directories = new HashMap<>();
  private final Map<Digest, ThrowingSupplier<InputStream, IOException>> dataMap;

  private final Delegate delegate;

  InputsDigestBuilder(Delegate delegate) {
    this(delegate, new HashMap<>());
  }

  private InputsDigestBuilder(
      Delegate delegate, Map<Digest, ThrowingSupplier<InputStream, IOException>> dataMap) {
    this.delegate = delegate;
    this.dataMap = dataMap;
  }

  /**
   * Holder for a digest and a supplier for the data. In some cases, the data itself will never be
   * needed.
   */
  static class DigestAndData {
    private final Digest digest;
    private final ThrowingSupplier<InputStream, IOException> dataSupplier;

    DigestAndData(Digest digest, ThrowingSupplier<InputStream, IOException> dataSupplier) {
      this.digest = digest;
      this.dataSupplier = dataSupplier;
    }
  }

  /** The delegate is required to provide the digests for data. */
  interface Delegate {
    DigestAndData digest(byte[] data);

    DigestAndData digest(Path path) throws IOException;

    DigestAndData digest(Directory directory) throws IOException;
  }

  /**
   * The default delegate uses a FileHashLoader for computing file digests and siphash24 for
   * byte[]/directory digests.
   */
  public static class DefaultDelegate implements Delegate {
    private final HashFunction hashFunction;
    private final Path rootPath;
    private final ThrowingFunction<Path, HashCode, IOException> fileHasher;

    public DefaultDelegate(
        Path rootPath, ThrowingFunction<Path, HashCode, IOException> fileHasher) {
      this.rootPath = rootPath;
      this.fileHasher = fileHasher;
      this.hashFunction = Hashing.sipHash24();
    }

    @Override
    public DigestAndData digest(byte[] data) {
      Digest digest = new Digest(hashFunction.hashBytes(data).toString(), data.length);
      return new DigestAndData(digest, () -> new ByteArrayInputStream(data));
    }

    @Override
    public DigestAndData digest(Path path) throws IOException {
      Preconditions.checkState(!path.isAbsolute());
      Path resolved = rootPath.resolve(path);
      Digest digest = new Digest(fileHasher.apply(resolved).toString(), Files.size(resolved));
      return new DigestAndData(digest, () -> Files.newInputStream(resolved));
    }

    @Override
    public DigestAndData digest(Directory directory) throws IOException {
      byte[] data = ThriftUtil.serialize(ThriftProtocol.COMPACT, directory);
      Digest digest = new Digest(hashFunction.hashBytes(data).toString(), data.length);
      return new DigestAndData(digest, () -> new ByteArrayInputStream(data));
    }
  }

  /** The computed inputs digest along with a map of all the data referenced from the root. */
  @Value.Immutable
  @BuckStyleTuple
  interface AbstractInputs {
    Digest getRootDigest();

    Map<Digest, ThrowingSupplier<InputStream, IOException>> getRequiredData();
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
    return Inputs.of(buildDigest(), dataMap);
  }

  private Digest buildDigest() throws IOException {
    List<FileNode> fileNodes =
        files.values().stream().sorted().collect(ImmutableList.toImmutableList());

    List<DirectoryNode> directoryNodes =
        RichStream.from(directories.entrySet())
            .map(
                ThrowingFunction.asFunction(
                    entry -> new DirectoryNode(entry.getKey(), entry.getValue().buildDigest())))
            .sorted()
            .collect(Collectors.toList());

    return register(delegate.digest(new Directory(fileNodes, directoryNodes)));
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
          ignored -> new FileNode(name, register(digestSupplier.asSupplier().get()), isExecutable));
    }
  }

  private InputsDigestBuilder getDirectory(String segment) {
    Preconditions.checkState(!files.containsKey(segment));
    return directories.computeIfAbsent(
        segment, ignored -> new InputsDigestBuilder(delegate, dataMap));
  }
}
