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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** The factory is used to avoid creation of {@link UnusedDependenciesFinder} when */
public class UnusedDependenciesFinderFactory implements AddsToRuleKey {
  @AddToRuleKey private final Optional<String> buildozerPath;
  @AddToRuleKey private final boolean onlyPrintCommands;

  @ExcludeFromRuleKey(
      reason = "includes source paths",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDeps> deps;

  @ExcludeFromRuleKey(
      reason = "includes source paths",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDeps> providedDeps;

  public UnusedDependenciesFinderFactory(
      Optional<String> buildozerPath,
      boolean onlyPrintCommands,
      ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDeps> deps,
      ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDeps> providedDeps) {
    this.buildozerPath = buildozerPath;
    this.onlyPrintCommands = onlyPrintCommands;
    this.deps = deps;
    this.providedDeps = providedDeps;
  }

  UnusedDependenciesFinder create(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathResolverAdapter sourcePathResolverAdapter,
      JavaBuckConfig.UnusedDependenciesAction unusedDependenciesAction) {
    return ImmutableUnusedDependenciesFinder.of(
        buildTarget,
        projectFilesystem,
        CompilerOutputPaths.getDepFilePath(buildTarget, projectFilesystem),
        deps,
        providedDeps,
        sourcePathResolverAdapter,
        unusedDependenciesAction,
        buildozerPath,
        onlyPrintCommands);
  }
}
