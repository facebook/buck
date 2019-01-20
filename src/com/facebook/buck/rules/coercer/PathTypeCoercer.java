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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.nio.file.Path;

public class PathTypeCoercer extends LeafTypeCoercer<Path> {

  private final LoadingCache<Path, LoadingCache<String, Path>> pathCache =
      CacheBuilder.newBuilder()
          .build(
              CacheLoader.from(
                  pathRelativeToProjectRoot -> {
                    return CacheBuilder.newBuilder()
                        .weakValues()
                        .build(
                            CacheLoader.from(
                                path -> pathRelativeToProjectRoot.resolve(path).normalize()));
                  }));

  @Override
  public Class<Path> getOutputClass() {
    return Path.class;
  }

  @Override
  public Path coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      String pathString = (String) object;
      if (pathString.isEmpty()) {
        throw new CoerceFailedException("invalid path");
      }
      return pathCache.getUnchecked(pathRelativeToProjectRoot).getUnchecked(pathString);
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }

  public enum PathExistenceVerificationMode {
    VERIFY,
    DO_NOT_VERIFY,
  }
}
