/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class HeaderPathNormalizer {

  private final SourcePathResolver pathResolver;

  /**
   * A mapping from absolute path of a header path (file or directory) to it's corresponding source
   * path.
   */
  private final ImmutableMap<Path, SourcePath> headers;

  /**
   * A mapping of unnormalized header paths that are used by the tooling to the absolute path
   * representation of the corresponding source path.
   */
  private final ImmutableMap<Path, SourcePath> normalized;

  protected HeaderPathNormalizer(
      SourcePathResolver pathResolver,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> normalized) {
    this.pathResolver = pathResolver;
    this.headers = headers;
    this.normalized = normalized;
  }

  public static HeaderPathNormalizer empty(SourcePathResolver pathResolver) {
    return new HeaderPathNormalizer(pathResolver, ImmutableMap.of(), ImmutableMap.of());
  }

  private static <T> Optional<Map.Entry<Path, T>> pathLookup(Path path, Map<Path, T> map) {
    while (path != null) {
      T res = map.get(path);
      if (res != null) {
        return Optional.of(new AbstractMap.SimpleEntry<>(path, res));
      }
      path = path.getParent();
    }
    return Optional.empty();
  }

  public Optional<Path> getAbsolutePathForUnnormalizedPath(Path unnormalizedPath) {
    Preconditions.checkArgument(unnormalizedPath.isAbsolute());
    Optional<Map.Entry<Path, SourcePath>> result = pathLookup(unnormalizedPath, normalized);
    if (!result.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        pathResolver
            .getAbsolutePath(result.get().getValue())
            .resolve(result.get().getKey().relativize(unnormalizedPath)));
  }

  /** @return the {@link SourcePath} which corresponds to the given absolute path. */
  public SourcePath getSourcePathForAbsolutePath(Path absolutePath) {
    Preconditions.checkArgument(absolutePath.isAbsolute());
    Optional<Map.Entry<Path, SourcePath>> path = pathLookup(absolutePath, headers);
    Preconditions.checkState(path.isPresent(), "no headers mapped to %s", absolutePath);
    return path.get().getValue();
  }

  public static class Builder {

    private final SourcePathResolver pathResolver;

    private final Map<Path, SourcePath> headers = new LinkedHashMap<>();
    private final Map<Path, SourcePath> normalized = new LinkedHashMap<>();

    public Builder(SourcePathResolver pathResolver) {
      this.pathResolver = pathResolver;
    }

    private <V> void put(Map<Path, V> map, Path key, V value) {
      Preconditions.checkArgument(key.isAbsolute());
      key = MorePaths.normalize(key);

      // Hack: Using dropInternalCaches here because `toString` is called on caches by
      // PathShortener, and many Paths are constructed and stored due to HeaderPathNormalizer
      // containing exported headers of all transitive dependencies of a library. This amounts to
      // large memory usage. See t15541313. Once that is fixed, this hack can be deleted.
      V previous = map.put(MorePaths.dropInternalCaches(key), value);
      Preconditions.checkState(
          previous == null || previous.equals(value),
          "Expected header path to be consistent but key %s mapped to different values: "
              + "(old: %s, new: %s)",
          key,
          previous,
          value);
    }

    public Builder addSymlinkTree(SourcePath root, ImmutableMap<Path, SourcePath> headerMap) {
      Path rootPath = pathResolver.getAbsolutePath(root);
      for (Map.Entry<Path, SourcePath> entry : headerMap.entrySet()) {
        addHeader(entry.getValue(), rootPath.resolve(entry.getKey()));
      }
      return this;
    }

    public Builder addHeader(SourcePath sourcePath, Path... unnormalizedPaths) {

      // Map the relative path of the header path to the header, as we serialize the source path
      // using it's relative path.
      put(headers, pathResolver.getAbsolutePath(sourcePath), sourcePath);

      // Add a normalization mapping for the absolute path.
      // We need it for prefix headers and in some rare cases, regular headers will also end up
      // with an absolute path in the depfile for a reason we ignore.
      put(normalized, pathResolver.getAbsolutePath(sourcePath), sourcePath);

      // Add a normalization mapping for any unnormalized paths passed in.
      for (Path unnormalizedPath : unnormalizedPaths) {
        put(normalized, unnormalizedPath, sourcePath);
      }

      return this;
    }

    public Builder addHeaderDir(SourcePath sourcePath) {
      return addHeader(sourcePath);
    }

    public Builder addPrefixHeader(SourcePath sourcePath) {
      return addHeader(sourcePath);
    }

    public HeaderPathNormalizer build() {
      return new HeaderPathNormalizer(
          pathResolver, ImmutableMap.copyOf(headers), ImmutableMap.copyOf(normalized));
    }
  }
}
