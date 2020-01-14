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
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Utility class to model Python binary/test rules as {@link PythonPackagable}s for graph traversals
 * (e.g. {@code PythonUtil.getAllComponents())}.
 */
@BuckStyleValue
abstract class PythonBinaryPackagable implements PythonPackagable {

  @Override
  public abstract BuildTarget getBuildTarget();

  abstract ProjectFilesystem getFilesystem();

  abstract ImmutableList<BuildRule> getPythonPackageDeps();

  abstract Optional<PythonMappedComponents> getPythonModules();

  abstract Optional<PythonMappedComponents> getPythonResources();

  @Override
  public abstract Optional<Boolean> isPythonZipSafe();

  @Value.Lazy
  public Optional<PythonMappedComponents> getPythonSources() {
    return getPythonModules()
        .<Optional<PythonMappedComponents>>map(
            components ->
                components.getComponents().isEmpty()
                    ? Optional.empty()
                    : Optional.of(
                        PythonMappedComponents.of(
                            ImmutableSortedMap.copyOf(
                                Maps.filterKeys(
                                    components.getComponents(),
                                    dst ->
                                        MorePaths.getFileExtension(dst)
                                            .equals(PythonUtil.SOURCE_EXT))))))
        .orElseGet(Optional::empty);
  }

  @Override
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonPackageDeps();
  }

  @Override
  public Optional<PythonMappedComponents> getPythonModules(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonModules();
  }

  @Override
  public Optional<PythonMappedComponents> getPythonResources(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonResources();
  }

  @Override
  public Optional<PythonComponents> getPythonBytecode(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getPythonSources()
        .map(
            sources -> {
              PythonCompileRule compileRule =
                  (PythonCompileRule)
                      graphBuilder.computeIfAbsent(
                          getBuildTarget()
                              .withAppendedFlavors(
                                  pythonPlatform.getFlavor(),
                                  cxxPlatform.getFlavor(),
                                  PythonLibraryDescription.LibraryType.COMPILE.getFlavor()),
                          target ->
                              PythonCompileRule.from(
                                  target,
                                  getFilesystem(),
                                  graphBuilder,
                                  pythonPlatform.getEnvironment(),
                                  sources,
                                  false));
              return compileRule.getCompiledSources();
            });
  }
}
