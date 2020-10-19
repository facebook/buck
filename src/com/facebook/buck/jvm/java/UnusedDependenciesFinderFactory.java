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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.CalculateAbi;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.modern.CellNameResolverSerialization;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

/**
 * The factory is used to avoid creation of {@link
 * com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder}
 */
public class UnusedDependenciesFinderFactory implements AddsToRuleKey {
  @AddToRuleKey private final Optional<String> buildozerPath;
  @AddToRuleKey private final boolean onlyPrintCommands;
  @AddToRuleKey private final boolean doUltralightChecking;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final ImmutableList<DependencyAndExportedDeps> deps;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final ImmutableList<DependencyAndExportedDeps> providedDeps;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final ImmutableList<String> exportedDeps;

  @ExcludeFromRuleKey(
      serialization = CellNameResolverSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final CellNameResolver cellNameResolver;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final ImmutableMap<String, RelPath> cellToPathMappings;

  public UnusedDependenciesFinderFactory(
      AbsPath ruleCellRoot,
      Optional<String> buildozerPath,
      boolean onlyPrintCommands,
      boolean doUltralightChecking,
      ActionGraphBuilder actionGraphBuilder,
      ImmutableSortedSet<BuildTarget> deps,
      ImmutableSortedSet<BuildTarget> providedDeps,
      ImmutableList<String> exportedDeps,
      CellPathResolver cellPathResolver) {
    this.buildozerPath = buildozerPath;
    this.onlyPrintCommands = onlyPrintCommands;
    this.deps = getDependencies(actionGraphBuilder, actionGraphBuilder.getAllRules(deps));
    this.providedDeps =
        getDependencies(actionGraphBuilder, actionGraphBuilder.getAllRules(providedDeps));
    this.exportedDeps = exportedDeps;
    this.doUltralightChecking = doUltralightChecking;
    this.cellNameResolver = cellPathResolver.getCellNameResolver();
    this.cellToPathMappings =
        CellPathResolverUtils.getCellToMapMappings(ruleCellRoot, cellPathResolver);
  }

  private ImmutableList<DependencyAndExportedDeps> getDependencies(
      ActionGraphBuilder actionGraphBuilder, SortedSet<BuildRule> targets) {
    ImmutableList.Builder<DependencyAndExportedDeps> builder = ImmutableList.builder();
    for (BuildRule rule : targets) {
      BuildTargetAndSourcePaths targetAndSourcePaths =
          getBuildTargetAndSourcePaths(rule, actionGraphBuilder);
      if (targetAndSourcePaths == null) {
        continue;
      }

      ImmutableList<DependencyAndExportedDeps> exportedDeps =
          rule instanceof ExportDependencies
              ? getDependencies(
                  actionGraphBuilder,
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .addAll(((ExportDependencies) rule).getExportedDeps())
                      .addAll(((ExportDependencies) rule).getExportedProvidedDeps())
                      .build())
              : ImmutableList.of();
      builder.add(new DependencyAndExportedDeps(targetAndSourcePaths, exportedDeps));
    }

    return builder.build();
  }

  private BuildTargetAndSourcePaths getBuildTargetAndSourcePaths(
      BuildRule rule, ActionGraphBuilder actionGraphBuilder) {
    if (!(rule instanceof JavaLibrary || rule instanceof CalculateAbi)) {
      return null;
    }

    if (rule instanceof JavaLibrary && ((JavaLibrary) rule).neverMarkAsUnusedDependency()) {
      return null;
    }

    SourcePath ruleOutput = rule.getSourcePathToOutput();
    SourcePath abiRuleOutput = getAbiPath(actionGraphBuilder, (HasJavaAbi) rule);
    return new BuildTargetAndSourcePaths(
        rule.getBuildTarget().getUnconfiguredBuildTarget().toString(), ruleOutput, abiRuleOutput);
  }

  private SourcePath getAbiPath(ActionGraphBuilder actionGraphBuilder, HasJavaAbi rule) {
    Optional<BuildTarget> abiJarTarget = getAbiJarTarget(rule);
    if (!abiJarTarget.isPresent()) {
      return null;
    }

    BuildRule abiJarRule = actionGraphBuilder.requireRule(abiJarTarget.get());
    return abiJarRule.getSourcePathToOutput();
  }

  private Optional<BuildTarget> getAbiJarTarget(HasJavaAbi dependency) {
    Optional<BuildTarget> abiJarTarget = dependency.getSourceOnlyAbiJar();
    if (!abiJarTarget.isPresent()) {
      abiJarTarget = dependency.getAbiJar();
    }
    return abiJarTarget;
  }

  /** Holder for a build target string and the source paths of its output. */
  static class BuildTargetAndSourcePaths implements AddsToRuleKey {
    @AddToRuleKey private final String buildTarget;
    @AddToRuleKey private final @Nullable SourcePath fullJarSourcePath;
    @AddToRuleKey private final @Nullable SourcePath abiSourcePath;

    BuildTargetAndSourcePaths(
        String buildTarget,
        @Nullable SourcePath fullJarSourcePath,
        @Nullable SourcePath abiSourcePath) {
      this.buildTarget = buildTarget;
      this.fullJarSourcePath = fullJarSourcePath;
      this.abiSourcePath = abiSourcePath;
    }
  }

  /** Recursive hierarchy of a single build target and its exported deps. */
  static class DependencyAndExportedDeps implements AddsToRuleKey {
    @AddToRuleKey private final BuildTargetAndSourcePaths dependency;
    @AddToRuleKey private final ImmutableList<DependencyAndExportedDeps> exportedDeps;

    DependencyAndExportedDeps(
        BuildTargetAndSourcePaths dependency,
        ImmutableList<DependencyAndExportedDeps> exportedDeps) {
      this.dependency = dependency;
      this.exportedDeps = exportedDeps;
    }
  }

  /** Creates a new {@link UnusedDependenciesFinder} instance. */
  public UnusedDependenciesFinder create(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathResolverAdapter resolver,
      JavaBuckConfig.UnusedDependenciesAction unusedDependenciesAction) {

    Path depFilePath = CompilerOutputPaths.getDepFilePath(buildTarget, filesystem.getBuckPaths());

    return UnusedDependenciesFinder.of(
        buildTarget,
        convert(deps, resolver, filesystem),
        convert(providedDeps, resolver, filesystem),
        exportedDeps,
        unusedDependenciesAction,
        buildozerPath,
        onlyPrintCommands,
        cellNameResolver,
        cellToPathMappings,
        filesystem.relativize(filesystem.resolve(depFilePath)),
        doUltralightChecking);
  }

  private ImmutableList<UnusedDependenciesFinder.DependencyAndExportedDepsPath> convert(
      ImmutableList<DependencyAndExportedDeps> list,
      SourcePathResolverAdapter resolver,
      ProjectFilesystem filesystem) {
    return list.stream()
        .map(dep -> convert(dep, resolver, filesystem))
        .collect(ImmutableList.toImmutableList());
  }

  private UnusedDependenciesFinder.DependencyAndExportedDepsPath convert(
      DependencyAndExportedDeps dependencyAndExportedDeps,
      SourcePathResolverAdapter resolver,
      ProjectFilesystem filesystem) {
    return new UnusedDependenciesFinder.DependencyAndExportedDepsPath(
        convert(dependencyAndExportedDeps.dependency, resolver, filesystem),
        dependencyAndExportedDeps.exportedDeps.stream()
            .map(d -> convert(d, resolver, filesystem))
            .collect(ImmutableList.toImmutableList()));
  }

  private UnusedDependenciesFinder.BuildTargetAndPaths convert(
      BuildTargetAndSourcePaths buildTargetAndSourcePaths,
      SourcePathResolverAdapter resolver,
      ProjectFilesystem filesystem) {
    return new UnusedDependenciesFinder.BuildTargetAndPaths(
        buildTargetAndSourcePaths.buildTarget,
        toRelativePath(buildTargetAndSourcePaths.fullJarSourcePath, resolver, filesystem),
        toRelativePath(buildTargetAndSourcePaths.abiSourcePath, resolver, filesystem));
  }

  private RelPath toRelativePath(
      SourcePath sourcePath, SourcePathResolverAdapter resolver, ProjectFilesystem filesystem) {
    if (sourcePath == null) {
      return null;
    }
    return filesystem.relativize(resolver.getAbsolutePath(sourcePath));
  }
}
