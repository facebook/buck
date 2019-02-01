/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

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
  private final CellPathResolver cellRoots;

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
    this.cellRoots = cellRoots;

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

    // Create symlink to the root cell.
    Path rootPath = cellRoots.getCellPathOrThrow(Optional.empty());
    Path relativePath = projectFilesystem.getRootPath().relativize(rootPath);
    cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));
    cellsNamesBuilder.add(Escaper.BASH_ESCAPER.apply(ROOT_CELL_LINK_NAME));

    // Create symlink to the cells.
    for (ImmutableMap.Entry<String, Path> ent : cellRoots.getCellPaths().entrySet()) {
      relativePath = projectFilesystem.getRootPath().relativize(ent.getValue());
      cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));
      cellsNamesBuilder.add(Escaper.BASH_ESCAPER.apply(ent.getKey()));
    }

    // Generate an .sh file that builds up an environment and invokes the user's script.
    // This generated .sh file will be returned by getExecutableCommand().
    // This script can be cached and used on machines other than the one where it was
    // created. That means it can't contain any absolute filepaths.

    ImmutableList.Builder<String> resourceStringsBuilder = new ImmutableList.Builder<String>();
    ImmutableSortedSet<Path> resourcesPaths =
        context.getSourcePathResolver().getAllAbsolutePaths(resources);

    for (Path resourcePath : resourcesPaths) {
      // Get the name of the cell containing the resource.
      Optional<String> resourceCellName = getCellNameForPath(resourcePath);
      // If resourceCellName is Optional.empty(), the call below will
      // return the path of the root cell.
      Path resourceCellPath = cellRoots.getCellPathOrThrow(resourceCellName);
      // Get the path relative to its cell.
      Path relativeResourcePath = resourceCellPath.relativize(resourcePath);
      // Get the path when referenced in the symlink created for the cell in the output folder.
      Path linkedResourcePath =
          Paths.get(resourceCellName.orElse(ROOT_CELL_LINK_NAME)).resolve(relativeResourcePath);
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
        runtimeResourcesDir.resolve(ROOT_CELL_LINK_NAME).resolve(getBuildTarget().getBasePath());

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

  private Optional<String> getCellNameForPath(Path path) {
    Optional<Optional<String>> result = Optional.empty();
    Path matchedPath = null;

    // Match root path.
    Optional<Optional<String>> rootCellName = Optional.of(Optional.empty());
    Path rootPath = cellRoots.getCellPathOrThrow(Optional.empty());
    if (path.startsWith(rootPath)) {
      result = rootCellName;
      matchedPath = rootPath;
    }

    // Match other cells.
    for (Map.Entry<String, Path> cellEntry : cellRoots.getCellPaths().entrySet()) {
      // Get the longest match.
      if (path.startsWith(cellEntry.getValue())
          && (matchedPath == null
              // Get the longest match.
              || cellEntry.getValue().toString().length() > matchedPath.toString().length())) {
        result = Optional.of(Optional.of(cellEntry.getKey()));
        matchedPath = cellEntry.getValue();
      }
    }

    // result can end up in three possible states:
    // - Optional.empty(): no cell has been matched.
    // - Optional.of(Optional.empty()): the root cell has been matched and there's no corresponding
    //        concrete cell.
    // - other values: an other cell has been matched.
    Preconditions.checkState(result.isPresent(), "path %s is not included in any cell", path);

    return result.get();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), output)))
        .addInput(main)
        .addInputs(resources)
        .build();
  }

  // If the script is generated from another build rule, it needs to be available on disk
  // for this rule to be usable.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(resources.stream(), Stream.of(main))
        .map(ruleFinder::filterBuildRuleInputs)
        .flatMap(ImmutableSet::stream)
        .map(BuildRule::getBuildTarget);
  }

  private Step getSymlinkStep(
      SourcePathResolver resolver, ProjectFilesystem projectFilesystem, Path buildCellRootPath) {
    ImmutableList<String> conflicts = searchForLinkConflicts(resolver);
    if (conflicts.isEmpty()) {
      return new SymlinkTreeStep(
          STEP_CATEGORY,
          projectFilesystem,
          buildCellRootPath,
          resources
              .stream()
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

  private Path getSymlinkPath(SourcePathResolver resolver, SourcePath input) {
    Path runtimeLinkPath = runtimeResourcesDir.resolve(deriveLinkPath(resolver, input));

    return getBuildTarget().getCellPath().resolve(runtimeLinkPath);
  }

  private ImmutableList<String> searchForLinkConflicts(SourcePathResolver resolver) {
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

  private Path deriveLinkPath(SourcePathResolver resolver, SourcePath sourcePath) {
    if (sourcePath instanceof DefaultBuildTargetSourcePath) {
      BuildTarget target = ((DefaultBuildTargetSourcePath) sourcePath).getTarget();

      // If resource is in a different cell, then link it under '__external__' with the cell name.
      Optional<String> cellName = getCellNameForPath(resolver.getAbsolutePath(sourcePath));
      Path mapped =
          cellName
              .map(cell -> Paths.get(EXTERNAL_CELL_LINK_NAME).resolve(cell))
              .orElse(Paths.get(ROOT_CELL_LINK_NAME));

      // Tack on the target's base path and the source path name of the resource
      return mapped.resolve(
          target.getBasePath().resolve(resolver.getSourcePathName(target, sourcePath)));
    }
    return Paths.get(ROOT_CELL_LINK_NAME).resolve(resolver.getRelativePath(sourcePath));
  }
}
