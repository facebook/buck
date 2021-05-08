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

package com.facebook.buck.core.build.context;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.jvm.core.JavaPackageFinder;

@BuckStyleValue
public abstract class BuildContext {

  public abstract SourcePathResolverAdapter getSourcePathResolver();

  /** @return the absolute path of the cell in which the build was invoked. */
  public abstract AbsPath getBuildCellRootPath();

  public abstract JavaPackageFinder getJavaPackageFinder();

  public abstract BuckEventBus getEventBus();

  public abstract boolean getShouldDeleteTemporaries();

  public abstract CellPathResolver getCellPathResolver();

  public static BuildContext of(
      SourcePathResolverAdapter sourcePathResolver,
      AbsPath buildCellRootPath,
      JavaPackageFinder javaPackageFinder,
      BuckEventBus eventBus,
      boolean shouldDeleteTemporaries,
      CellPathResolver cellPathResolver) {
    return ImmutableBuildContext.ofImpl(
        sourcePathResolver,
        buildCellRootPath,
        javaPackageFinder,
        eventBus,
        shouldDeleteTemporaries,
        cellPathResolver);
  }

  /** Update field. */
  public BuildContext withBuildCellRootPath(AbsPath buildCellRootPath) {
    if (getBuildCellRootPath().equals(buildCellRootPath)) {
      return this;
    }
    return of(
        getSourcePathResolver(),
        buildCellRootPath,
        getJavaPackageFinder(),
        getEventBus(),
        getShouldDeleteTemporaries(),
        getCellPathResolver());
  }

  public BuildContext withEventBus(BuckEventBus eventBus) {
    if (getEventBus() == eventBus) {
      return this;
    }
    return of(
        getSourcePathResolver(),
        getBuildCellRootPath(),
        getJavaPackageFinder(),
        eventBus,
        getShouldDeleteTemporaries(),
        getCellPathResolver());
  }

  public BuildContext withJavaPackageFinder(JavaPackageFinder javaPackageFinder) {
    if (getJavaPackageFinder() == javaPackageFinder) {
      return this;
    }
    return of(
        getSourcePathResolver(),
        getBuildCellRootPath(),
        javaPackageFinder,
        getEventBus(),
        getShouldDeleteTemporaries(),
        getCellPathResolver());
  }

  public BuildContext withSourcePathResolver(SourcePathResolverAdapter sourcePathResolver) {
    if (getSourcePathResolver() == sourcePathResolver) {
      return this;
    }
    return of(
        sourcePathResolver,
        getBuildCellRootPath(),
        getJavaPackageFinder(),
        getEventBus(),
        getShouldDeleteTemporaries(),
        getCellPathResolver());
  }
}
