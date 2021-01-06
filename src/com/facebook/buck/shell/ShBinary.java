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

package com.facebook.buck.shell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.Escaper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class ShBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, HasRuntimeDeps {

  private static final Path TEMPLATE =
      Paths.get(
          System.getProperty(
              "buck.path_to_sh_binary_template", "src/com/facebook/buck/shell/sh_binary_template"));

  private static final String RUNTIME_RESOURCES_DIR = "runtime_resources";

  private static final String ROOT_CELL_LINK_NAME = "__default__";
  private static final String EXTERNAL_CELL_LINK_NAME = "__external__";

  private static final String STEP_CATEGORY = "sh_binary";

  @AddToRuleKey private final SourcePath main;
  @AddToRuleKey private final ImmutableSet<SourcePath> resources;

  private final CellNameResolver cellNameResolver;
  private final NewCellPathResolver cellPathResolver;

  /** The path where the output will be written. */
  private final Path output;

  private final Path runtimeResourcesDir;

  protected ShBinary(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePath main,
      ImmutableSet<SourcePath> resources) {
    super(buildTarget, projectFilesystem, params);
    this.main = main;
    this.resources = resources;
    this.cellNameResolver = cellRoots.getCellNameResolver();
    this.cellPathResolver = cellRoots.getNewCellPathResolver();

    this.output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(),
            buildTarget,
            String.format("__%%s__/%s.sh", buildTarget.getShortNameAndFlavorPostfix()));

    this.runtimeResourcesDir =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(), this.getBuildTarget(), "__%s__/" + RUNTIME_RESOURCES_DIR);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<String> cellsPathsStringsBuilder = new ImmutableList.Builder<String>();
    ImmutableList.Builder<String> cellsNamesBuilder = new ImmutableList.Builder<String>();

    ProjectFilesystem projectFilesystem = getProjectFilesystem();

    // TODO(cjhopman): Is this supposed to be to the root cell?
    // Create symlink to our own cell.
    CanonicalCellName thisCellCanonicalName = cellNameResolver.getName(Optional.empty());
    Path rootPath = cellPathResolver.getCellPath(thisCellCanonicalName);
    RelPath relativePath = projectFilesystem.getRootPath().relativize(rootPath);
    cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));
    cellsNamesBuilder.add(Escaper.BASH_ESCAPER.apply(ROOT_CELL_LINK_NAME));

    // Create symlink to the cells.
    for (Map.Entry<Optional<String>, CanonicalCellName> alias :
        cellNameResolver.getKnownCells().entrySet()) {
      if (!alias.getKey().isPresent()) {
        continue;
      }
      Path cellPath = cellPathResolver.getCellPath(alias.getValue());
      relativePath = projectFilesystem.getRootPath().relativize(cellPath);
      cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));
      cellsNamesBuilder.add(Escaper.BASH_ESCAPER.apply(alias.getKey().get()));
    }

    // Generate an .sh file that builds up an environment and invokes the user's script.
    // This generated .sh file will be returned by getExecutableCommand().
    // This script can be cached and used on machines other than the one where it was
    // created. That means it can't contain any absolute filepaths.

    ImmutableList.Builder<String> resourceStringsBuilder = new ImmutableList.Builder<String>();
    ImmutableSortedSet<Path> resourcesPaths =
        context.getSourcePathResolver().getAllAbsolutePaths(resources);

    for (Path resourcePath : resourcesPaths) {
      // TODO(cjhopman) This is probably wrong. Shouldn't the resource appear under every cell
      // alias?

      // Get the name of the cell containing the resource.
      CellLookupResult lookupResult = lookupCellForPath(resourcePath);
      // If resourceCellName is Optional.empty(), the call below will
      // return the path of the root cell.

      // Get the path relative to its cell.
      Path relativeResourcePath = lookupResult.getPath().relativize(resourcePath);
      // Get the path when referenced in the symlink created for the cell in the output folder.
      Path linkedResourcePath =
          Paths.get(lookupResult.getAlias().orElse(ROOT_CELL_LINK_NAME))
              .resolve(relativeResourcePath);
      resourceStringsBuilder.add(Escaper.BASH_ESCAPER.apply(linkedResourcePath.toString()));
    }

    ImmutableList<String> resourceStrings = resourceStringsBuilder.build();
    Path pathToProjectRoot =
        output.getParent().relativize(projectFilesystem.getBuckPaths().getProjectRootDir());

    // Configure the template output.
    String escapedProjectRoot = Escaper.escapeAsBashString(pathToProjectRoot);
    ImmutableMap.Builder<String, Object> valuesBuilder = ImmutableMap.builder();
    valuesBuilder.put("path_to_project_root_file", escapedProjectRoot);
    valuesBuilder.put(
        "script_to_run",
        Escaper.escapeAsBashString(context.getSourcePathResolver().getRelativePath(main)));
    valuesBuilder.put("resources", resourceStrings);
    valuesBuilder.put("cell_names", cellsNamesBuilder.build());
    valuesBuilder.put("cell_paths", cellsPathsStringsBuilder.build());

    Path defaultRuntimeResourcesPath =
        runtimeResourcesDir
            .resolve(ROOT_CELL_LINK_NAME)
            .resolve(
                getBuildTarget()
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPath(runtimeResourcesDir.getFileSystem()));

    String defaultRuntimeResources = Escaper.escapeAsBashString(defaultRuntimeResourcesPath);

    valuesBuilder.put("default_runtime_resources", defaultRuntimeResources);
    valuesBuilder.put(
        "external_runtime_resources",
        Escaper.escapeAsBashString(runtimeResourcesDir.resolve(EXTERNAL_CELL_LINK_NAME)));

    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .add(
            getSymlinkStep(
                context.getSourcePathResolver(),
                getProjectFilesystem(),
                context.getBuildCellRootPath()))
        .add(
            new StringTemplateStep(TEMPLATE, getProjectFilesystem(), output, valuesBuilder.build()))
        .add(new MakeExecutableStep(getProjectFilesystem(), output))
        .build();
  }

  /** Simple internal data holder. */
  @BuckStyleValue
  interface CellLookupResult {
    Optional<String> getAlias();

    Path getPath();
  }

  private CellLookupResult lookupCellForPath(Path path) {
    @Nullable Optional<String> result = null;
    @Nullable Path matchedPath = null;

    for (Map.Entry<Optional<String>, CanonicalCellName> alias :
        cellNameResolver.getKnownCells().entrySet()) {
      Path cellPath = cellPathResolver.getCellPath(alias.getValue());
      if (path.startsWith(cellPath)
          && (matchedPath == null || matchedPath.getNameCount() < cellPath.getNameCount())) {
        result = alias.getKey();
        matchedPath = cellPath;
      }
    }

    return ImmutableCellLookupResult.of(
        Objects.requireNonNull(result, String.format("path %s is not included in any cell", path)),
        Objects.requireNonNull(
            matchedPath, String.format("path %s is not included in any cell", path)));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), output)))
        .addInput(main)
        .addInputs(resources)
        .build();
  }

  // If the script is generated from another build rule, it needs to be available on disk
  // for this rule to be usable.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(resources.stream(), Stream.of(main))
        .map(buildRuleResolver::filterBuildRuleInputs)
        .flatMap(ImmutableSet::stream)
        .map(BuildRule::getBuildTarget);
  }

  private Step getSymlinkStep(
      SourcePathResolverAdapter resolver,
      ProjectFilesystem projectFilesystem,
      Path buildCellRootPath) {
    ImmutableList<String> conflicts = searchForLinkConflicts(resolver);
    if (conflicts.isEmpty()) {
      return new SymlinkTreeStep(
          STEP_CATEGORY,
          projectFilesystem,
          buildCellRootPath,
          resources.stream()
              .collect(
                  ImmutableMap.toImmutableMap(
                      input -> getSymlinkPath(resolver, input),
                      input -> resolver.getAbsolutePath(input))));
    }
    return new AbstractExecutionStep(STEP_CATEGORY + "_link_conflicts") {
      @Override
      public StepExecutionResult execute(ExecutionContext context) {
        for (String conflict : conflicts) {
          context.getBuckEventBus().post(ConsoleEvent.create(Level.SEVERE, conflict));
        }
        return StepExecutionResults.ERROR;
      }
    };
  }

  private Path getSymlinkPath(SourcePathResolverAdapter resolver, SourcePath input) {
    Path runtimeLinkPath = runtimeResourcesDir.resolve(deriveLinkPath(resolver, input));

    return getProjectFilesystem().resolve(runtimeLinkPath);
  }

  private ImmutableList<String> searchForLinkConflicts(SourcePathResolverAdapter resolver) {
    Map<Path, Path> linkPaths = new HashMap<>();
    ImmutableList.Builder<String> conflicts = ImmutableList.builder();
    for (SourcePath sourcePath : resources) {
      Path linkPath = deriveLinkPath(resolver, sourcePath);
      if (!linkPaths.containsKey(linkPath)) {
        linkPaths.put(linkPath, resolver.getRelativePath(sourcePath));
      } else {
        conflicts.add(
            String.format(
                "Duplicate resource link path '%s' (Resolves to both '%s' and '%s')",
                linkPath, sourcePath, linkPaths.get(linkPath)));
      }
    }
    return conflicts.build();
  }

  private Path deriveLinkPath(SourcePathResolverAdapter resolver, SourcePath sourcePath) {
    if (sourcePath instanceof DefaultBuildTargetSourcePath) {
      BuildTarget target = ((DefaultBuildTargetSourcePath) sourcePath).getTarget();

      // If resource is in a different cell, then link it under '__external__' with the cell name.
      Optional<String> cellName =
          lookupCellForPath(resolver.getAbsolutePath(sourcePath)).getAlias();
      Path mapped =
          cellName
              .map(cell -> Paths.get(EXTERNAL_CELL_LINK_NAME).resolve(cell))
              .orElse(Paths.get(ROOT_CELL_LINK_NAME));

      // Tack on the target's base path and the source path name of the resource
      return mapped.resolve(
          target
              .getCellRelativeBasePath()
              .getPath()
              .toPathDefaultFileSystem()
              .resolve(resolver.getSourcePathName(target, sourcePath)));
    }
    return Paths.get(ROOT_CELL_LINK_NAME).resolve(resolver.getRelativePath(sourcePath));
  }

  @Override
  public boolean isCacheable() {
    // Caching for ShBinary rules is broken, as the runtime resources symlinks
    // currently don't get created if the script is retrieved from the cache.
    return false;
  }
}
