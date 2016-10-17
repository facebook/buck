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

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Function;
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
    return new HeaderPathNormalizer(
        pathResolver,
        ImmutableMap.of(),
        ImmutableMap.of());
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
    Optional<Map.Entry<Path, SourcePath>> result = pathLookup(unnormalizedPath, normalized);
    if (!result.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        pathResolver.getAbsolutePath(result.get().getValue())
            .resolve(result.get().getKey().relativize(unnormalizedPath)));
  }

  /**
   * @return a normalizer function that can be used to convert paths used by the tooling into paths
   *    that can cached.
   */
  public Optional<Path> getRelativePathForUnnormalizedPath(Path unnormalizedPath) {
    Optional<Map.Entry<Path, SourcePath>> result = pathLookup(unnormalizedPath, normalized);
    if (!result.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        pathResolver.getRelativePath(result.get().getValue())
            .resolve(result.get().getKey().relativize(unnormalizedPath)));
  }

  /**
   * @return the {@link SourcePath} which corresponds to the given absolute path.
   */
  public SourcePath getSourcePathForAbsolutePath(Path absolutePath) {
    Preconditions.checkArgument(absolutePath.isAbsolute());
    Optional<Map.Entry<Path, SourcePath>> path = pathLookup(absolutePath, headers);
    Preconditions.checkState(
        path.isPresent(),
        "no headers mapped to %s",
        absolutePath);
    return path.get().getValue();
  }

  public static class Builder {

    private final SourcePathResolver pathResolver;
    private final Function<Path, Path> pathTransformer;

    private final Map<Path, SourcePath> headers = new LinkedHashMap<>();
    private final Map<Path, SourcePath> normalized = new LinkedHashMap<>();

    public Builder(
        SourcePathResolver pathResolver,
        Function<Path, Path> pathTransformer) {
      this.pathResolver = pathResolver;
      this.pathTransformer = pathTransformer;
    }

    private <K, V> void put(Map<K, V> map, K key, V value) {
      V previous = map.put(key, value);
      Preconditions.checkState(previous == null || previous.equals(value));
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

      // Add a normaliation mapping for the unnormalized absolute path to the relative path.
      put(normalized,
          Preconditions.checkNotNull(
              pathTransformer.apply(pathResolver.getAbsolutePath(sourcePath))),
          sourcePath);

      // Add a normalization mapping for any unnormalized paths passed in.
      for (Path unnormalizedPath : unnormalizedPaths) {
        put(normalized,
            Preconditions.checkNotNull(pathTransformer.apply(unnormalizedPath)),
            sourcePath);
      }

      return this;
    }

    public Builder addHeaderDir(SourcePath sourcePath, Path... unnormalizedPaths) {
      return addHeader(sourcePath, unnormalizedPaths);
    }

    public HeaderPathNormalizer build() {
      return new HeaderPathNormalizer(
          pathResolver,
          ImmutableMap.copyOf(headers),
          ImmutableMap.copyOf(normalized));
    }

  }

}
