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
import com.facebook.buck.rules.modern.builders.thrift.OutputDirectory;
import com.facebook.buck.rules.modern.builders.thrift.OutputFile;
import com.facebook.buck.rules.modern.builders.thrift.Tree;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker;
import com.google.devtools.build.lib.concurrent.StripedKeyedLocker;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;

/** A simple, on-disk content addressed storage. */
public class LocalContentAddressedStorage implements ContentAddressedStorage {
  private final Path cacheDir;
  private final StripedKeyedLocker<String> fileLock = new StripedKeyedLocker<>(8);
  private final ThrowingFunction<Directory, Digest, IOException> directoryDigester;

  public LocalContentAddressedStorage(
      Path cacheDir, ThrowingFunction<Directory, Digest, IOException> directoryDigester) {
    this.cacheDir = cacheDir;
    this.directoryDigester = directoryDigester;
  }

  /** For any digests that are missing, adds the corresponding data to the storage. */
  @Override
  public void addMissing(ImmutableMap<Digest, ThrowingSupplier<InputStream, IOException>> data)
      throws IOException {
    Stream<Digest> missing = findMissing(data.keySet());
    for (Entry<Digest, ThrowingSupplier<InputStream, IOException>> entry :
        missing.collect(ImmutableMap.toImmutableMap(digest -> digest, data::get)).entrySet()) {
      String hash = entry.getKey().hash;
      Path path = ensureParent(getPath(hash));
      try (AutoUnlocker ignored = fileLock.writeLock(hash)) {
        if (Files.exists(path)) {
          continue;
        }
        Path tempPath = path.getParent().resolve(path.getFileName() + ".tmp");
        try (FileOutputStream outputStream = new FileOutputStream(tempPath.toFile())) {
          ByteStreams.copy(entry.getValue().get(), outputStream);
        }
        Files.move(tempPath, path);
      }
    }
  }

  /**
   * Materializes the outputs into the build root. All required data must be present (or inlined).
   */
  @Override
  public void materializeOutputs(
      List<OutputDirectory> outputDirectories, List<OutputFile> outputFiles, Path root)
      throws IOException {
    for (OutputFile file : outputFiles) {
      Path path = root.resolve(file.path);
      ensureParent(path);
      if (file.isSetContent()) {
        try (FileChannel output =
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
          output.write(file.content);
        }
      } else {
        Files.write(path, getData(file.digest));
      }
      if (file.isExecutable) {
        Preconditions.checkState(path.toFile().setExecutable(true));
      }
    }

    for (OutputDirectory directory : outputDirectories) {
      Path dirRoot = root.resolve(directory.path);
      Tree tree = readTree(directory.treeDigest);
      Map<Digest, Directory> childMap =
          tree.children
              .stream()
              .collect(ImmutableMap.toImmutableMap(directoryDigester.asFunction(), child -> child));
      materializeDirectory(childMap, tree.root, dirRoot);
    }
  }

  private void materializeDirectory(Map<Digest, Directory> childMap, Directory dir, Path root)
      throws IOException {
    Files.createDirectories(root);
    for (DirectoryNode childNode : dir.directories) {
      materializeDirectory(childMap, childMap.get(childNode.digest), root.resolve(childNode.name));
    }

    for (FileNode file : dir.files) {
      materializeFile(root, file);
    }
  }

  /** Materializes all of the inputs into root. All required data must be present. */
  @Override
  public void materializeInputs(Path root, Digest inputsDigest) throws IOException {
    Directory dir = readDirectory(inputsDigest);

    Files.createDirectories(root);
    for (FileNode file : dir.files) {
      materializeFile(root, file);
    }
    for (DirectoryNode child : dir.directories) {
      materializeInputs(root.resolve(child.name), child.digest);
    }
  }

  @VisibleForTesting
  byte[] getData(Digest digest) throws IOException {
    Path path = getPath(digest.hash);
    Preconditions.checkState(Files.exists(path));
    return Files.readAllBytes(path);
  }

  private Path ensureParent(Path path) throws IOException {
    MoreFiles.createParentDirectories(path);
    return path;
  }

  private Path getPath(String hashString) {
    return cacheDir
        .resolve(hashString.substring(0, 2))
        .resolve(hashString.substring(2, 4))
        .resolve(hashString);
  }

  private Tree readTree(Digest digest) throws IOException {
    Path path = getPath(digest.hash);
    Preconditions.checkState(Files.exists(path));
    Tree tree = new Tree();
    ThriftUtil.deserialize(ThriftProtocol.COMPACT, new FileInputStream(path.toFile()), tree);
    return tree;
  }

  private Directory readDirectory(Digest digest) throws IOException {
    Path path = getPath(digest.hash);
    Preconditions.checkState(Files.exists(path));
    Directory directory = new Directory();
    ThriftUtil.deserialize(ThriftProtocol.COMPACT, new FileInputStream(path.toFile()), directory);
    return directory;
  }

  private void materializeFile(Path dir, FileNode file) throws IOException {
    Path path = getPath(file.digest.hash);
    Preconditions.checkState(Files.exists(path));
    // As this file could potentially be materialized as both executable and non-executable, and
    // links share that, we need two concrete versions of the file.
    if (file.isExecutable) {
      Path exePath = path.getParent().resolve(path.getFileName() + ".x");
      if (!Files.exists(exePath)) {
        try (AutoUnlocker ignored = fileLock.writeLock(exePath.toString())) {
          if (!Files.exists(exePath)) {
            Path tempPath = path.getParent().resolve(path.getFileName() + ".x.tmp");
            Files.copy(path, tempPath);
            Preconditions.checkState(tempPath.toFile().setExecutable(true));
            Files.move(tempPath, exePath);
          }
        }
      }
      path = exePath;
    }
    Files.createLink(dir.resolve(file.name), path);
  }

  private Stream<Digest> findMissing(Set<Digest> digests) {
    return digests.stream().filter(digest -> !Files.exists(getPath(digest.hash)));
  }
}
