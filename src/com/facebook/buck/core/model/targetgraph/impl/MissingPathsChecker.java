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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Checks that paths exist and throw an exception if at least one path doesn't exist. */
class MissingPathsChecker implements PathsChecker {

  private final LoadingCache<AbsPath, Set<ForwardRelativePath>> pathsCache =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(CacheLoader.from(rootPath -> Sets.newConcurrentHashSet()));

  @Override
  public void checkPaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      ImmutableSet<ForwardRelativePath> paths) {
    Set<ForwardRelativePath> checkedPaths =
        pathsCache.getUnchecked(projectFilesystem.getRootPath());
    for (ForwardRelativePath path : paths) {
      if (!checkedPaths.add(path)) {
        continue;
      }

      try {
        projectFilesystem.readAttributes(
            path.toPath(projectFilesystem.getFileSystem()), BasicFileAttributes.class);
      } catch (NoSuchFileException e) {
        throw new HumanReadableException(
            e, "%s references non-existing file or directory '%s'", buildTarget, path);
      } catch (IOException e) {
        throw new HumanReadableException(
            e,
            "%s references inaccessible file or directory '%s': %s",
            buildTarget,
            path,
            e.getMessage());
      }
    }
  }
}
