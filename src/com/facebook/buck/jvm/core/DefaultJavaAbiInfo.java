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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.util.unarchive.Unzip;
import com.facebook.infer.annotation.Assertions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.jar.JarFile;
import javax.annotation.Nullable;

/** The default inmplementation of JavaAbiInfo. */
public class DefaultJavaAbiInfo implements JavaAbiInfo {
  private final BuildTarget buildTarget;
  private final SourcePath jarSourcePath;
  @Nullable private volatile JarContents jarContents;

  public DefaultJavaAbiInfo(SourcePath jarPath) {
    Objects.requireNonNull(jarPath);
    Preconditions.checkState(jarPath instanceof BuildTargetSourcePath);
    buildTarget = ((BuildTargetSourcePath) jarPath).getTarget();
    jarSourcePath = jarPath;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return Objects.requireNonNull(jarContents, "Must call load first.").contents;
  }

  @Override
  public boolean jarContains(String path) {
    return Objects.requireNonNull(jarContents, "Must call load first.")
        .contentPaths
        .contains(Paths.get(path));
  }

  @Override
  public void load(SourcePathResolver pathResolver) throws IOException {
    Preconditions.checkState(
        jarContents == null,
        "load() called without a preceding invalidate(). This usually indicates "
            + "that a rule is calling load in initializeFromDisk() but failing to call "
            + "invalidate() in invalidateInitializeFromDiskState().");
    jarContents = JarContents.load(pathResolver, jarSourcePath);
  }

  @Override
  public void invalidate() {
    jarContents = null;
  }

  private static class JarContents {
    private final ImmutableSortedSet<SourcePath> contents;
    private final ImmutableSet<Path> contentPaths;

    public JarContents(ImmutableSortedSet<SourcePath> contents, ImmutableSet<Path> contentPaths) {
      this.contents = contents;
      this.contentPaths = contentPaths;
    }

    static JarContents load(SourcePathResolver resolver, SourcePath jarSourcePath)
        throws IOException {
      ImmutableSortedSet<SourcePath> contents;
      Path jarAbsolutePath = resolver.getAbsolutePath(jarSourcePath);
      if (Files.isDirectory(jarAbsolutePath)) {
        BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) jarSourcePath;
        contents =
            Files.walk(jarAbsolutePath)
                .filter(path -> !path.endsWith(JarFile.MANIFEST_NAME))
                .map(
                    path ->
                        ExplicitBuildTargetSourcePath.of(buildTargetSourcePath.getTarget(), path))
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      } else {
        SourcePath nonNullJarSourcePath = Assertions.assertNotNull(jarSourcePath);
        contents =
            Unzip.getZipMembers(jarAbsolutePath).stream()
                .filter(path -> !path.endsWith(JarFile.MANIFEST_NAME))
                .map(path -> ArchiveMemberSourcePath.of(nonNullJarSourcePath, path))
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      }
      return new JarContents(
          contents,
          contents.stream()
              .map(
                  sourcePath -> {
                    if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
                      return ((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath();
                    } else {
                      return ((ArchiveMemberSourcePath) sourcePath).getMemberPath();
                    }
                  })
              .collect(ImmutableSet.toImmutableSet()));
    }
  }
}
