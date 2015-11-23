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

package com.facebook.buck.rules;

import com.facebook.buck.model.Pair;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Manifest {

  private static final int VERSION = 0;

  private final List<String> headers;
  private final Map<String, Integer> headerIndices;

  private final List<Pair<Integer, HashCode>> hashes;
  private final Map<HashCode, Integer> hashIndices;

  private final List<Pair<RuleKey, int[]>> entries;

  /**
   * Create an empty manifest.
   */
  public Manifest() {
    headers = new ArrayList<>();
    headerIndices = new HashMap<>();
    hashes = new ArrayList<>();
    hashIndices = new HashMap<>();
    entries = new ArrayList<>();
  }

  /**
   * Deserialize an existing manifest from the given {@link InputStream}.
   */
  public Manifest(InputStream rawInput) throws IOException {
    DataInputStream input = new DataInputStream(rawInput);

    Preconditions.checkState(input.readInt() == VERSION);

    int numberOfHeaders = input.readInt();
    headers = new ArrayList<>(numberOfHeaders);
    headerIndices = new HashMap<>(numberOfHeaders);
    for (int index = 0; index < numberOfHeaders; index++) {
      String header = input.readUTF();
      headers.add(header);
      headerIndices.put(header, index);
    }

    int numberOfHashes = input.readInt();
    hashes = new ArrayList<>(numberOfHashes);
    hashIndices = new HashMap<>(numberOfHashes);
    for (int index = 0; index < numberOfHashes; index++) {
      int headerIndex = input.readInt();
      HashCode headerHash = HashCode.fromString(input.readUTF());
      hashes.add(new Pair<>(headerIndex, headerHash));
      hashIndices.put(headerHash, index);
    }

    int numberOfEntries = input.readInt();
    entries = new ArrayList<>(numberOfEntries);
    for (int entryIndex = 0; entryIndex < numberOfEntries; entryIndex++) {
      int numberOfEntryHashes = input.readInt();
      int[] entryHashes = new int[numberOfEntryHashes];
      for (int hashIndex = 0; hashIndex < numberOfEntryHashes; hashIndex++) {
        entryHashes[hashIndex] = input.readInt();
      }
      RuleKey key = new RuleKey(input.readUTF());
      entries.add(new Pair<>(key, entryHashes));
    }
  }

  private Integer addHash(String header, HashCode hash) {
    Integer headerIndex = headerIndices.get(header);
    if (headerIndex == null) {
      headers.add(header);
      headerIndex = headers.size() - 1;
      headerIndices.put(header, headerIndex);
    }

    Integer hashIndex = hashIndices.get(hash);
    if (hashIndex == null) {
      hashes.add(new Pair<>(headerIndex, hash));
      hashIndex = hashes.size() - 1;
      hashIndices.put(hash, hashIndex);
    }

    return hashIndex;
  }

  @VisibleForTesting
  protected static HashCode hashSourcePathGroup(
      FileHashCache fileHashCache,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> paths)
      throws IOException {
    if (paths.size() == 1) {
      return fileHashCache.get(resolver.getAbsolutePath(paths.asList().get(0)));
    }
    Hasher hasher = Hashing.md5().newHasher();
    for (SourcePath path : paths) {
      hasher.putBytes(fileHashCache.get(resolver.getAbsolutePath(path)).asBytes());
    }
    return hasher.hash();
  }

  private boolean hashesMatch(
      FileHashCache fileHashCache,
      SourcePathResolver resolver,
      ImmutableListMultimap<Path, SourcePath> universe,
      int[] hashIndices)
      throws IOException {
    for (int hashIndex : hashIndices) {
      Pair<Integer, HashCode> hashEntry = hashes.get(hashIndex);
      Path header = Paths.get(headers.get(hashEntry.getFirst()));
      ImmutableList<SourcePath> candidates = universe.get(header);
      if (candidates.isEmpty()) {
        return false;
      }
      HashCode onDiskHeaderHash;
      try {
        onDiskHeaderHash = hashSourcePathGroup(fileHashCache, resolver, candidates);
      } catch (NoSuchFileException e) {
        return false;
      }
      HashCode headerHash = hashEntry.getSecond();
      if (!headerHash.equals(onDiskHeaderHash)) {
        return false;
      }
    }
    return true;
  }

  /**
   * @return the {@link RuleKey} of the entry that matches the on disk hashes provided by
   *     {@code fileHashCache}.
   */
  public Optional<RuleKey> lookup(
      FileHashCache fileHashCache,
      SourcePathResolver resolver,
      ImmutableSet<SourcePath> universe)
      throws IOException {
    ImmutableListMultimap<Path, SourcePath> mappedUniverse =
        Multimaps.index(universe, resolver.getRelativePathFunction());
    for (Pair<RuleKey, int[]> entry : entries) {
      if (hashesMatch(fileHashCache, resolver, mappedUniverse, entry.getSecond())) {
        return Optional.of(entry.getFirst());
      }
    }
    return Optional.absent();
  }

  /**
   * Adds a new output file to the manifest.
   */
  public void addEntry(
      FileHashCache fileHashCache,
      RuleKey key,
      SourcePathResolver resolver,
      ImmutableSet<SourcePath> universe,
      ImmutableSet<SourcePath> inputs)
      throws IOException {
    int index = 0;
    int[] hashIndices = new int[inputs.size()];
    ImmutableListMultimap<Path, SourcePath> sortedUniverse =
        Multimaps.index(
            universe,
            resolver.getRelativePathFunction());
    for (SourcePath input : inputs) {
      Path relativePath = resolver.getRelativePath(input);
      ImmutableList<SourcePath> paths = sortedUniverse.get(relativePath);
      Preconditions.checkState(!paths.isEmpty());
      hashIndices[index++] =
          addHash(
              relativePath.toString(),
              hashSourcePathGroup(fileHashCache, resolver, paths));
    }
    entries.add(new Pair<>(key, hashIndices));
  }

  /**
   * Serializes the manifest to the given {@link OutputStream}.
   */
  public void serialize(OutputStream rawOutput) throws IOException {
    DataOutputStream output = new DataOutputStream(rawOutput);

    output.writeInt(VERSION);

    output.writeInt(headers.size());
    for (String header : headers) {
      output.writeUTF(header);
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

  @VisibleForTesting
  ImmutableMap<RuleKey, ImmutableMap<String, HashCode>> toMap() {
    ImmutableMap.Builder<RuleKey, ImmutableMap<String, HashCode>> builder = ImmutableMap.builder();
    for (Pair<RuleKey, int[]> entry : entries) {
      ImmutableMap.Builder<String, HashCode> entryBuilder = ImmutableMap.builder();
      for (int hashIndex : entry.getSecond()) {
        Pair<Integer, HashCode> hashEntry = hashes.get(hashIndex);
        String header = headers.get(hashEntry.getFirst());
        HashCode headerHash = hashEntry.getSecond();
        entryBuilder.put(header, headerHash);
      }
      builder.put(entry.getFirst(), entryBuilder.build());
    }
    return builder.build();
  }

  @VisibleForTesting
  static Manifest fromMap(ImmutableMap<RuleKey, ImmutableMap<String, HashCode>> map) {
    Manifest manifest = new Manifest();
    for (Map.Entry<RuleKey, ImmutableMap<String, HashCode>> entry : map.entrySet()) {
      int entryHashIndex = 0;
      int[] entryHashIndices = new int[entry.getValue().size()];
      for (Map.Entry<String, HashCode> innerEntry : entry.getValue().entrySet()) {
        entryHashIndices[entryHashIndex++] =
            manifest.addHash(innerEntry.getKey(), innerEntry.getValue());
      }
      manifest.entries.add(new Pair<>(entry.getKey(), entryHashIndices));
    }
    return manifest;
  }

}
