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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;

/** Filesystem params used in {@link CompileToJarStepFactory}. */
@BuckStyleValue
public abstract class FilesystemParams {

  public abstract AbsPath getRootPath();

  public abstract BaseBuckPaths getBaseBuckPaths();

  public abstract ImmutableSet<PathMatcher> getIgnoredPaths();

  /** Creates {@link FilesystemParams} */
  public static FilesystemParams of(
      AbsPath rootPath, BaseBuckPaths baseBuckPaths, ImmutableSet<PathMatcher> ignoredPaths) {
    return ImmutableFilesystemParams.ofImpl(rootPath, baseBuckPaths, ignoredPaths);
  }

  /** Creates {@link FilesystemParams} */
  public static FilesystemParams of(ProjectFilesystem projectFilesystem) {
    return of(
        projectFilesystem.getRootPath(),
        projectFilesystem.getBuckPaths(),
        projectFilesystem.getIgnoredPaths());
  }
}
