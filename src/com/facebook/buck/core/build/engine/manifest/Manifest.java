/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.manifest;

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public class Manifest {

  private static final Logger LOG = Logger.get(Manifest.class);

  private static final int VERSION = 0;

  private final RuleKey key;

  @VisibleForTesting final List<String> inputs;
  private final Map<String, Integer> inputIndices;

  @VisibleForTesting final List<Pair<Integer, HashCode>> hashes;
  private final Map<HashCode, Integer> hashIndices;

  @VisibleForTesting final List<Pair<RuleKey, int[]>> entries;

  /** Create an empty manifest. */
  public Manifest(RuleKey key) {
    this.key = key;
    inputs = new ArrayList<>();
    inputIndices = new HashMap<>();
    hashes = new ArrayList<>();
    hashIndices = new HashMap<>();
    entries = new ArrayList<>();
  }

  /** Deserialize an existing manifest from the given {@link InputStream}. */
  public Manifest(InputStream rawInput) throws IOException {
    DataInputStream input = new DataInputStream(rawInput);

    // Verify the manifest version.
    int version = input.readInt();
    Preconditions.checkState(version == VERSION, "invalid version: %s != %s", version, VERSION);

    key = new RuleKey(input.readUTF());

    int numberOfHeaders = input.readInt();
    LOG.verbose("%s: loading %d input entries", this.key, numberOfHeaders);
    inputs = new ArrayList<>(numberOfHeaders);
    inputIndices = new HashMap<>(numberOfHeaders);
    for (int index = 0; index < numberOfHeaders; index++) {
      String inputName = input.readUTF();
      inputs.add(inputName);
      inputIndices.put(inputName, index);
    }

    int numberOfHashes = input.readInt();
    LOG.verbose("%s: loading %d hash entries", this.key, numberOfHashes);
    hashes = new ArrayList<>(numberOfHashes);
    hashIndices = new HashMap<>(numberOfHashes);
    for (int index = 0; index < numberOfHashes; index++) {
      int inputIndex = input.readInt();
      HashCode inputHash = HashCode.fromString(input.readUTF());
      hashes.add(new Pair<>(inputIndex, inputHash));
      hashIndices.put(inputHash, index);
    }

    int numberOfEntries = input.readInt();
    LOG.verbose("%s: loading %d dep file rule key entries", this.key, numberOfEntries);
    entries = new ArrayList<>(numberOfEntries);
    for (int entryIndex = 0; entryIndex < numberOfEntries; entryIndex++) {
      int numberOfEntryHashes = input.readInt();
      int[] entryHashes = new int[numberOfEntryHashes];
      for (int hashIndex = 0; hashIndex < numberOfEntryHashes; hashIndex++) {
        entryHashes[hashIndex] = input.readInt();
      }
      RuleKey key = new RuleKey(input.readUTF());
      LOG.verbose("%s: loaded entry for dep file rule key %s", this.key, key);
      entries.add(new Pair<>(key, entryHashes));
    }
  }

  public RuleKey getKey() {
    return key;
  }

  @VisibleForTesting
  Integer addHash(String input, HashCode hash) {
    Integer inputIndex = inputIndices.get(input);
    if (inputIndex == null) {
      inputs.add(input);
      inputIndex = inputs.size() - 1;
      inputIndices.put(input, inputIndex);
    }

    Integer hashIndex = hashIndices.get(hash);
    if (hashIndex == null) {
      hashes.add(new Pair<>(inputIndex, hash));
      hashIndex = hashes.size() - 1;
      hashIndices.put(hash, hashIndex);
    }

    return hashIndex;
  }

  /** Hash the files pointed to by the source paths. */
  @VisibleForTesting
  static HashCode hashSourcePathGroup(
      FileHashLoader fileHashLoader, SourcePathResolver resolver, ImmutableList<SourcePath> paths)
      throws IOException {
    if (paths.size() == 1) {
      return hashSourcePath(paths.get(0), fileHashLoader, resolver);
    }
    Hasher hasher = Hashing.md5().newHasher();
    for (SourcePath path : paths) {
      hasher.putBytes(hashSourcePath(path, fileHashLoader, resolver).asBytes());
    }
    return hasher.hash();
  }

  private static HashCode hashSourcePath(
      SourcePath path, FileHashLoader fileHashLoader, SourcePathResolver resolver)
      throws IOException {
    if (path instanceof ArchiveMemberSourcePath) {
      return fileHashLoader.get(
          resolver.getFilesystem(path), resolver.getRelativeArchiveMemberPath(path));
    } else {
      return fileHashLoader.get(resolver.getFilesystem(path), resolver.getRelativePath(path));
    }
  }

  private boolean hashesMatch(
      FileHashLoader fileHashLoader,
      SourcePathResolver resolver,
      ImmutableListMultimap<String, SourcePath> universe,
      int[] hashIndices)
      throws IOException {
    for (int hashIndex : hashIndices) {
      Pair<Integer, HashCode> hashEntry = hashes.get(hashIndex);
      String input = inputs.get(hashEntry.getFirst());
      ImmutableList<SourcePath> candidates = universe.get(input);
      if (candidates.isEmpty()) {
        return false;
      }
      HashCode onDiskHeaderHash;
      try {
        onDiskHeaderHash = hashSourcePathGroup(fileHashLoader, resolver, candidates);
      } catch (NoSuchFileException e) {
        return false;
      }
      HashCode inputHash = hashEntry.getSecond();
      if (!inputHash.equals(onDiskHeaderHash)) {
        return false;
      }
    }
    return true;
  }

  /**
   * @return the {@link RuleKey} of the entry that matches the on disk hashes provided by {@code
   *     fileHashLoader}.
   */
  public Optional<RuleKey> lookup(
      FileHashLoader fileHashLoader, SourcePathResolver resolver, ImmutableSet<SourcePath> universe)
      throws IOException {
    // Create a set of all paths we care about.
    ImmutableSet.Builder<String> interestingPathsBuilder = new ImmutableSet.Builder<>();
    for (Pair<?, int[]> entry : entries) {
      for (int hashIndex : entry.getSecond()) {
        interestingPathsBuilder.add(inputs.get(hashes.get(hashIndex).getFirst()));
      }
    }
    ImmutableSet<String> interestingPaths = interestingPathsBuilder.build();

    // Create a multimap from paths we care about to SourcePaths that maps to them.
    ImmutableListMultimap<String, SourcePath> mappedUniverse =
        index(
            universe,
            path -> sourcePathToManifestHeader(path, resolver),
            interestingPaths::contains);

    // Find a matching entry.
    for (Pair<RuleKey, int[]> entry : entries) {
      if (hashesMatch(fileHashLoader, resolver, mappedUniverse, entry.getSecond())) {
        return Optional.of(entry.getFirst());
      }
    }
    return Optional.empty();
  }

  private static String sourcePathToManifestHeader(SourcePath input, SourcePathResolver resolver) {
    return sourcePathToManifestPathKey(input, resolver).toString();
  }

  /**
   * Converting Path to String is expensive when using BuckUnixPath so if we just need a key
   * representation of a manifest path, use the corresponding Path/ArchiveMemberPath.
   */
  private static Object sourcePathToManifestPathKey(SourcePath input, SourcePathResolver resolver) {
    if (input instanceof ArchiveMemberSourcePath) {
      return resolver.getRelativeArchiveMemberPath(input);
    } else {
      return resolver.getRelativePath(input);
    }
  }

  /** Adds a new output file to the manifest. */
  public void addEntry(
      FileHashLoader fileHashLoader,
      RuleKey key,
      SourcePathResolver resolver,
      ImmutableSet<SourcePath> universe,
      ImmutableSet<SourcePath> inputs)
      throws IOException {

    // Construct the input sub-paths that we care about.
    ImmutableSet<Object> inputPaths =
        RichStream.from(inputs)
            .map(path -> sourcePathToManifestPathKey(path, resolver))
            .toImmutableSet();

    // Create a multimap from paths we care about to SourcePaths that maps to them.
    ImmutableListMultimap<Object, SourcePath> sortedUniverse =
        index(universe, path -> sourcePathToManifestPathKey(path, resolver), inputPaths::contains);

    // Record the Entry.
    int index = 0;
    int[] hashIndices = new int[inputs.size()];
    for (Object relativePath : inputPaths) {
      ImmutableList<SourcePath> paths = sortedUniverse.get(relativePath);
      Preconditions.checkState(!paths.isEmpty());
      hashIndices[index++] =
          addHash(relativePath.toString(), hashSourcePathGroup(fileHashLoader, resolver, paths));
    }
    entries.add(new Pair<>(key, hashIndices));
  }

  /** Serializes the manifest to the given {@link OutputStream}. */
  public void serialize(OutputStream rawOutput) throws IOException {
    DataOutputStream output = new DataOutputStream(rawOutput);

    output.writeInt(VERSION);

    output.writeUTF(key.toString());

    output.writeInt(inputs.size());
    for (String input : inputs) {
      output.writeUTF(input);
    }

    output.writeInt(hashes.size());
    for (Pair<Integer, HashCode> hash : hashes) {
      output.writeInt(hash.getFirst());
      output.writeUTF(hash.getSecond().toString());
    }

    output.writeInt(entries.size());
    for (Pair<RuleKey, int[]> entry : entries) {
      output.writeInt(entry.getSecond().length);
      for (int hashIndex : entry.getSecond()) {
        output.writeInt(hashIndex);
      }
      output.writeUTF(entry.getFirst().toString());
    }
  }

  public int size() {
    return entries.size();
  }

  /**
   * Create a multimap that's the result of apply the function to the input values, filtered by a
   * predicate.
   *
   * <p>This is conceptually similar to {@code filterKeys(index(values, keyFunc), filter)}, but much
   * more efficient as it doesn't construct entries that will be filtered out.
   */
  private static <K, V> ImmutableListMultimap<K, V> index(
      Iterable<V> values, Function<V, K> keyFunc, Predicate<K> keyFilter) {
    ImmutableListMultimap.Builder<K, V> builder = new ImmutableListMultimap.Builder<>();
    for (V value : values) {
      K key = keyFunc.apply(value);
      if (keyFilter.test(key)) {
        builder.put(key, value);
      }
    }
    return builder.build();
  }

  public ManifestStats getStats() {
    return ManifestStats.builder()
        .setNumDepFiles(entries.size())
        .setNumInputs(inputs.size())
        .setNumHashes(hashes.size())
        .build();
  }
}
