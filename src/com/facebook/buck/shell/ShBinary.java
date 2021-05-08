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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
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
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.log.Logger;
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
import com.google.common.annotations.VisibleForTesting;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// TODO(cjhopman): Don't allow people to just write complex, hard to follow behavior without
// documenting it.
// TODO(cjhopman): Figure out wtf this code does. It's creating symlinks all over the place, some of
// them within this things own getBuildSteps, others by passing information into the generated
// template and then creating them when invoked. It looks like it's creating multiple symlinks for
// everything that appears in resources. Why does it do that? And where are they going?
public class ShBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, HasRuntimeDeps {

  private static final Logger LOG = Logger.get(ShBinary.class);

  private static final Path TEMPLATE =
      Paths.get(
          System.getProperty(
              "buck.path_to_sh_binary_template", "src/com/facebook/buck/shell/sh_binary_template"));

  private static final String RUNTIME_RESOURCES_DIR = "runtime_resources";

  private static final String ROOT_CELL_LINK_NAME = "__default__";

  private static final String STEP_CATEGORY = "sh_binary";

  @AddToRuleKey private final SourcePath main;
  @AddToRuleKey private final ImmutableSet<SourcePath> resources;

  private final CellNameResolver cellNameResolver;
  private final NewCellPathResolver cellPathResolver;

  /** The path where the output will be written. */
  private final RelPath output;

  private final RelPath runtimeResourcesDir;

  /**
   * Pattern to match incorrectly generated `CELL_NAME` section in sh binary. The pattern we are
   * trying to match is where we have the following code:
   *
   * <pre>{@code
   * ...
   * CELL_NAMES=(
   *   ...
   *   header "<valid cell names>"
   *   ...
   * )
   * ...
   * }</pre>
   */
  @VisibleForTesting
  static final Pattern cellErrorMatcher =
      Pattern.compile(
          "CELLS_NAMES\\=\\((((\\s)*?\"(\\w|[-_])*?\")*?(\\s)*?header ((\\s)*?\"(\\w|[-_])*?\")*?)*?\\s*\\)");

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
            getProjectFilesystem().getBuckPaths(),
            buildTarget,
            String.format("__%%s__/%s.sh", buildTarget.getShortNameAndFlavorPostfix()));

    this.runtimeResourcesDir =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(),
            this.getBuildTarget(),
            "__%s__/" + RUNTIME_RESOURCES_DIR);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output.getPath());

    ImmutableList.Builder<String> cellsPathsStringsBuilder = new ImmutableList.Builder<String>();
    ImmutableList.Builder<String> cellsNamesBuilder = new ImmutableList.Builder<String>();

    ProjectFilesystem projectFilesystem = getProjectFilesystem();

    // TODO(cjhopman): Is this supposed to be to the root cell?
    // Create symlink to our own cell.
    CanonicalCellName thisCellCanonicalName = cellNameResolver.getName(Optional.empty());
    AbsPath rootPath = cellPathResolver.getCellPath(thisCellCanonicalName);
    RelPath relativePath = projectFilesystem.getRootPath().relativize(rootPath);
    cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));
    cellsNamesBuilder.add(Escaper.BASH_ESCAPER.apply(ROOT_CELL_LINK_NAME));

    // Create symlink to the cells.
    for (Map.Entry<Optional<String>, CanonicalCellName> alias :
        cellNameResolver.getKnownCells().entrySet()) {
      if (!alias.getKey().isPresent()) {
        continue;
      }
      AbsPath cellPath = cellPathResolver.getCellPath(alias.getValue());
      relativePath = projectFilesystem.getRootPath().relativize(cellPath);
      cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));

      String cellName = Escaper.BASH_ESCAPER.apply(alias.getKey().get());
      cellsNamesBuilder.add(cellName);
    }

    // Generate an .sh file that builds up an environment and invokes the user's script.
    // This generated .sh file will be returned by getExecutableCommand().
    // This script can be cached and used on machines other than the one where it was
    // created. That means it can't contain any absolute filepaths.

    ImmutableList.Builder<String> resourceStringsBuilder = new ImmutableList.Builder<String>();
    ImmutableSortedSet<AbsPath> resourcesPaths =
        context.getSourcePathResolver().getAllAbsolutePaths(resources);

    for (AbsPath resourcePath : resourcesPaths) {
      // TODO(cjhopman) This is probably wrong. Shouldn't the resource appear under every cell
      // alias?

      // Get the path relative to its cell.
      RelPath relativeResourcePath =
          cellPathResolver.getCellPath(getBuildTarget().getCell()).relativize(resourcePath);
      // Get the path when referenced in the symlink created for the cell in the output folder.
      RelPath linkedResourcePath = RelPath.get(ROOT_CELL_LINK_NAME).resolve(relativeResourcePath);
      resourceStringsBuilder.add(Escaper.BASH_ESCAPER.apply(linkedResourcePath.toString()));
    }

    ImmutableList<String> resourceStrings = resourceStringsBuilder.build();
    RelPath pathToProjectRoot =
        output.getParent().relativize(projectFilesystem.getBuckPaths().getProjectRootDir());

    // Configure the template output.
    String escapedProjectRoot = Escaper.escapeAsBashString(pathToProjectRoot);
    ImmutableMap.Builder<String, Object> valuesBuilder = ImmutableMap.builder();
    valuesBuilder.put("path_to_project_root_file", escapedProjectRoot);
    valuesBuilder.put(
        "script_to_run",
        Escaper.escapeAsBashString(context.getSourcePathResolver().getCellUnsafeRelPath(main)));
    valuesBuilder.put("resources", resourceStrings);
    valuesBuilder.put("cell_names", cellsNamesBuilder.build());
    valuesBuilder.put("cell_paths", cellsPathsStringsBuilder.build());

    // TODO(cjhopman): Why is this so similar to the resource path construction above? What's the
    // difference and why?
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

    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .add(
            getSymlinkStep(
                context.getSourcePathResolver(),
                getProjectFilesystem(),
                context.getBuildCellRootPath().getPath()))
        .add(
            new StringTemplateStep(
                TEMPLATE,
                output.getPath(),
                valuesBuilder.build(),
                expanded -> {
                  Matcher matcher = cellErrorMatcher.matcher(expanded);
                  if (matcher.find()) {
                    String matched = matcher.group();
                    LOG.error(
                        "ShBinary template CELL_NAMES expanded to: `%s`, but given `%s`",
                        matched, cellsNamesBuilder.build());
                  }
                }))
        .add(new MakeExecutableStep(getProjectFilesystem(), output))
        .build();
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
                      input -> resolver.getAbsolutePath(input).getPath())));
    }
    return new AbstractExecutionStep(STEP_CATEGORY + "_link_conflicts") {
      @Override
      public StepExecutionResult execute(StepExecutionContext context) {
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
        linkPaths.put(linkPath, resolver.getCellUnsafeRelPath(sourcePath).getPath());
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

      if (!target.getCell().equals(this.getBuildTarget().getCell())) {
        throw new RuntimeException(
            String.format(
                "cross-cell resources are not implemented: %s in sh_binary %s",
                sourcePath, this.getBuildTarget()));
      }

      // Tack on the target's base path and the source path name of the resource
      return Paths.get(ROOT_CELL_LINK_NAME)
          .resolve(
              target
                  .getCellRelativeBasePath()
                  .getPath()
                  .toPathDefaultFileSystem()
                  .resolve(resolver.getSourcePathName(target, sourcePath)));
    } else if (sourcePath instanceof PathSourcePath) {
      return Paths.get(ROOT_CELL_LINK_NAME)
          .resolve(resolver.getCellUnsafeRelPath(sourcePath).getPath());
    } else {
      throw new RuntimeException(
          "unknown source path: " + sourcePath + "for sh_binary " + this.getBuildTarget());
    }
  }

  @Override
  public boolean isCacheable() {
    // Caching for ShBinary rules is broken, as the runtime resources symlinks
    // currently don't get created if the script is retrieved from the cache.
    return false;
  }
}
