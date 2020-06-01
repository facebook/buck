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

package com.facebook.buck.features.python;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.step.fs.SymlinkPaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Contains Python components (e.g. modules, resources) to be used by an executing Python binary
 * step (e.g. {@link PexStep}).
 */
@BuckStyleValueWithBuilder
abstract class PythonResolvedPackageComponents {

  protected abstract PythonResolvedComponentsGroup getModules();

  protected abstract PythonResolvedComponentsGroup getResources();

  protected abstract PythonResolvedComponentsGroup getNativeLibraries();

  protected abstract Optional<Path> getDefaultInitPy();

  protected abstract Optional<Boolean> isZipSafe();

  /** Run {@code consumer} on all modules, throwing an error on duplicates. */
  public void forEachModule(PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    getModules().forEachModule(getDefaultInitPy(), consumer);
  }

  /** Run {@code consumer} on all resources, throwing an error on duplicates. */
  public void forEachResource(PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    getResources().forEachComponent("resource", consumer);
  }

  /** Run {@code consumer} on all native libraries, throwing an error on duplicates. */
  public void forEachNativeLibrary(PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    getNativeLibraries().forEachComponent("native library", consumer);
  }

  @VisibleForTesting
  ImmutableMap<Path, Path> getAllModules() throws IOException {
    ImmutableMap.Builder<Path, Path> builder = ImmutableMap.builder();
    forEachModule(builder::put);
    return builder.build();
  }

  @VisibleForTesting
  ImmutableMap<Path, Path> getAllResources() throws IOException {
    ImmutableMap.Builder<Path, Path> builder = ImmutableMap.builder();
    forEachResource(builder::put);
    return builder.build();
  }

  @VisibleForTesting
  ImmutableMap<Path, Path> getAllNativeLibraries() throws IOException {
    ImmutableMap.Builder<Path, Path> builder = ImmutableMap.builder();
    forEachNativeLibrary(builder::put);
    return builder.build();
  }

  /**
   * @return this object as a {@link SymlinkPaths} for use with {@link
   *     com.facebook.buck.step.fs.SymlinkTreeMergeStep}.
   */
  public SymlinkPaths asSymlinkPaths() {
    return consumer -> {
      forEachModule(consumer::accept);
      forEachResource(consumer::accept);
      forEachNativeLibrary(consumer::accept);
    };
  }
}
