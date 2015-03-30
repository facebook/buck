/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.util.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * FileHashCache that is populated with a fixed set of entries.
 */
public class FakeFileHashCache implements FileHashCache {

  public static final FakeFileHashCache EMPTY_CACHE =
      createFromStrings(Maps.<String, String>newHashMap());

  private final ImmutableMap<Path, HashCode> pathsToHashes;

  public FakeFileHashCache(Map<Path, HashCode> pathsToHashes) {
    this.pathsToHashes = ImmutableMap.copyOf(pathsToHashes);
  }

  public static FakeFileHashCache createFromStrings(Map<String, String> pathsToHashes) {
    ImmutableMap.Builder<Path, HashCode> builder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : pathsToHashes.entrySet()) {
      builder.put(Paths.get(entry.getKey()), HashCode.fromString(entry.getValue()));
    }
    return new FakeFileHashCache(builder.build());
  }

  @Override
  public boolean contains(Path path) {
    return pathsToHashes.containsKey(path);
  }

  @Override
  public HashCode get(Path path) {
    return Preconditions.checkNotNull(pathsToHashes.get(path), path.toString());
  }

}
