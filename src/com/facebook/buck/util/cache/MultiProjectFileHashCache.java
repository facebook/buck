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

package com.facebook.buck.util.cache;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class MultiProjectFileHashCache implements FileHashCache {

  private final ImmutableList<? extends ProjectFileHashCache> projectCaches;

  public MultiProjectFileHashCache(ImmutableList<? extends ProjectFileHashCache> projectCaches) {
    this.projectCaches = projectCaches;
  }

  private Optional<Pair<FileHashCache, Path>> lookup(Path path) {
    for (ProjectFileHashCache cache : projectCaches) {
      ProjectFilesystem filesystem = cache.getFilesystem();
      Optional<Path> relativePath = filesystem.getPathRelativeToProjectRoot(path);
      if (relativePath.isPresent() && !filesystem.isIgnored(relativePath.get())) {
        return Optional.of(new Pair<FileHashCache, Path>(cache, relativePath.get()));
      }
    }
    return Optional.absent();
  }

  @Override
  public boolean contains(Path path) {
    Optional<Pair<FileHashCache, Path>> found = lookup(path);
    return found.isPresent() && found.get().getFirst().contains(found.get().getSecond());
  }

  @Override
  public void invalidate(Path path) {
    Optional<Pair<FileHashCache, Path>> found = lookup(path);
    if (found.isPresent()) {
      found.get().getFirst().invalidate(found.get().getSecond());
    }
  }

  @Override
  public void invalidateAll() {
    for (ProjectFileHashCache cache : projectCaches) {
      cache.invalidateAll();
    }
  }

  @Override
  public HashCode get(Path path) throws IOException {
    Optional<Pair<FileHashCache, Path>> found = lookup(path);
    if (!found.isPresent()) {
      throw new NoSuchFileException(path.toString());
    }
    return found.get().getFirst().get(found.get().getSecond());
  }

}
