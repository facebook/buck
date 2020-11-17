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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.util.unarchive.Unzip;
import com.facebook.infer.annotation.Assertions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarFile;
import javax.annotation.Nullable;

/** The default implementation of JavaAbiInfo. */
public class DefaultJavaAbiInfo extends DefaultBaseJavaAbiInfo implements JavaAbiInfo {

  private final SourcePath jarSourcePath;
  private final BuildTarget buildTarget;
  @Nullable private volatile JarContents jarContents;

  private DefaultJavaAbiInfo(SourcePath jarPath, BuildTarget buildTarget) {
    super(buildTarget.getUnflavoredBuildTarget().toString());
    this.buildTarget = buildTarget;
    this.jarSourcePath = jarPath;
  }

  /** Creates {@link DefaultJavaAbiInfo} */
  public static DefaultJavaAbiInfo of(SourcePath jarPath) {
    return new DefaultJavaAbiInfo(jarPath, extractBuildTargetFromSourcePath(jarPath));
  }

  /**
   * Extracts {@link BuildTarget} from a given {@code sourcePath} assuming that source path type is
   * {@link BuildTargetSourcePath}
   */
  public static BuildTarget extractBuildTargetFromSourcePath(SourcePath sourcePath) {
    Objects.requireNonNull(sourcePath);
    Preconditions.checkState(sourcePath instanceof BuildTargetSourcePath);
    return ((BuildTargetSourcePath) sourcePath).getTarget();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return Objects.requireNonNull(jarContents, "Must call load() first.").contents;
  }

  @Override
  public void load(SourcePathResolverAdapter pathResolver) throws IOException {
    Preconditions.checkState(
        jarContents == null,
        "load() called without a preceding invalidate(). This usually indicates "
            + "that a rule is calling load in initializeFromDisk() but failing to call "
            + "invalidate() in invalidateInitializeFromDiskState().");
    jarContents = JarContents.load(pathResolver, jarSourcePath);
    setContentPaths(jarContents.contentPaths);
  }

  @Override
  public void invalidate() {
    jarContents = null;
    setContentPaths(null);
  }

  private static class JarContents {

    private final ImmutableSortedSet<SourcePath> contents;
    private final ImmutableSet<Path> contentPaths;

    public JarContents(ImmutableSortedSet<SourcePath> contents, ImmutableSet<Path> contentPaths) {
      this.contents = contents;
      this.contentPaths = contentPaths;
    }

    static JarContents load(SourcePathResolverAdapter resolver, SourcePath jarSourcePath)
        throws IOException {
      ImmutableSortedSet<SourcePath> contents;
      AbsPath jarAbsolutePath = resolver.getAbsolutePath(jarSourcePath);
      if (Files.isDirectory(jarAbsolutePath.getPath())) {
        BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) jarSourcePath;
        contents =
            Files.walk(jarAbsolutePath.getPath())
                .filter(path -> !path.endsWith(JarFile.MANIFEST_NAME))
                .map(
                    path ->
                        ExplicitBuildTargetSourcePath.of(buildTargetSourcePath.getTarget(), path))
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      } else {
        SourcePath nonNullJarSourcePath = Assertions.assertNotNull(jarSourcePath);
        contents =
            Unzip.getZipMembers(jarAbsolutePath.getPath()).stream()
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
