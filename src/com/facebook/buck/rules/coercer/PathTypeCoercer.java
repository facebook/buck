/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;

/** Coerce to {@link java.nio.file.Path}. */
public class PathTypeCoercer extends LeafUnconfiguredOnlyCoercer<Path> {

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
  public TypeToken<Path> getUnconfiguredType() {
    return TypeToken.of(Path.class);
  }

  @Override
  public Path coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      String pathString = (String) object;
      if (pathString.isEmpty()) {
        throw new CoerceFailedException("invalid path");
      }
      try {
        Path fsPath = pathRelativeToProjectRoot.toPath(filesystem.getFileSystem());
        Path resultPath = pathCache.getUnchecked(fsPath).getUnchecked(pathString);
        if (resultPath.isAbsolute()) {
          throw CoerceFailedException.simple(
              object, getOutputType(), "Path cannot contain an absolute path");
        }
        if (resultPath.startsWith("..")) {
          throw CoerceFailedException.simple(
              object, getOutputType(), "Path cannot point to above repository root");
        }
        return resultPath;
      } catch (UncheckedExecutionException e) {
        throw new CoerceFailedException(
            String.format("Could not convert '%s' to a Path", pathString), e.getCause());
      }
    } else {
      throw CoerceFailedException.simple(object, getOutputType());
    }
  }

  public enum PathExistenceVerificationMode {
    VERIFY,
    DO_NOT_VERIFY,
  }
}
