/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Utility class to model Python binary/test rules as {@link PythonPackagable}s for graph traversals
 * (e.g. {@code PythonUtil.getAllComponents())}.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractPythonBinaryPackagable implements PythonPackagable {

  @Override
  public abstract BuildTarget getBuildTarget();

  abstract ImmutableList<BuildRule> getPythonPackageDeps();

  @Value.NaturalOrder
  abstract ImmutableSortedMap<Path, SourcePath> getPythonModules();

  @Value.NaturalOrder
  abstract ImmutableSortedMap<Path, SourcePath> getPythonResources();

  @Override
  public abstract Optional<Boolean> isPythonZipSafe();

  @Override
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonPackageDeps();
  }

  @Override
  public ImmutableSortedMap<Path, SourcePath> getPythonModules(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonModules();
  }

  @Override
  public ImmutableSortedMap<Path, SourcePath> getPythonResources(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonResources();
  }
}
