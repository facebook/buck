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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
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
public class ShBinary extends ModernBuildRule<ShBinary.Impl>
    implements BinaryBuildRule, HasRuntimeDeps {

  private static final Logger LOG = Logger.get(ShBinary.class);

  private static final String ROOT_CELL_LINK_NAME = "__default__";
  private static final String STEP_CATEGORY = "sh_binary";
  private static final Path TEMPLATE_PATH =
      Paths.get(
          System.getProperty(
              "buck.path_to_sh_binary_template", "src/com/facebook/buck/shell/sh_binary_template"));

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
  static final Pattern CELL_ERROR_MATCHER =
      Pattern.compile(
          "CELLS_NAMES\\=\\((((\\s)*?\"(\\w|[-_])*?\")*?(\\s)*?header ((\\s)*?\"(\\w|[-_])*?\")*?)*?\\s*\\)");

  protected ShBinary(
      BuildTarget buildTarget,
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath main,
      ImmutableSet<SourcePath> resources) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            main,
            deriveResourceMap(ruleFinder.getSourcePathResolver(), resources),
            buildTarget,
            cellPathResolver.getCurrentCellName(),
            StringTemplateStep.readTemplateAsString(TEMPLATE_PATH)));
  }

  private static ImmutableMap<SourcePath, Optional<String>> deriveResourceMap(
      SourcePathResolverAdapter sourcePathResolver, ImmutableSet<SourcePath> resources) {
    ImmutableMap.Builder<SourcePath, Optional<String>> resourceMapBuilder =
        ImmutableMap.builderWithExpectedSize(resources.size());
    for (SourcePath sourcePath : resources) {
      Optional<BuildTarget> buildTarget = getBuildTarget(sourcePath);
      if (buildTarget.isPresent()) {
        resourceMapBuilder.put(
            sourcePath,
            Optional.of(sourcePathResolver.getSourcePathName(buildTarget.get(), sourcePath)));
      } else {
        resourceMapBuilder.put(sourcePath, Optional.empty());
      }
    }
    return resourceMapBuilder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    Impl buildable = getBuildable();
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .addInput(buildable.main)
        .addInputs(buildable.resources.keySet())
        .build();
  }

  // If the script is generated from another build rule, it needs to be available on disk
  // for this rule to be usable.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(getBuildable().resources.keySet().stream(), Stream.of(getBuildable().main))
        .map(buildRuleResolver::filterBuildRuleInputs)
        .flatMap(ImmutableSet::stream)
        .map(BuildRule::getBuildTarget);
  }

  @Override
  public boolean isCacheable() {
    // Caching for ShBinary rules is broken, as the runtime resources symlinks
    // currently don't get created if the script is retrieved from the cache.
    return false;
  }

  /** Internal Buildable for {@link ShBinary} rule. */
  static class Impl implements Buildable {

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final SourcePath main;
    @AddToRuleKey private final ImmutableMap<SourcePath, Optional<String>> resources;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final OutputPath runtimeResourcesDir;
    @AddToRuleKey private final Optional<String> currentCellName;
    @AddToRuleKey private final String template;

    protected Impl(
        SourcePath main,
        ImmutableMap<SourcePath, Optional<String>> resources,
        BuildTarget buildTarget,
        CanonicalCellName currentCanonicalCellName,
        String template) {
      this.buildTarget = buildTarget;
      this.main = main;
      this.resources = resources;
      this.output = new OutputPath(buildTarget.getShortName() + ".sh");
      this.runtimeResourcesDir = new OutputPath("runtime_resources");
      this.currentCellName = currentCanonicalCellName.getLegacyName();
      this.template = template;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      CellPathResolver cellPathResolver = buildContext.getCellPathResolver();
      NewCellPathResolver newCellPathResolver =
          buildContext.getCellPathResolver().getNewCellPathResolver();

      ImmutableList.Builder<String> cellsPathsStringsBuilder = new ImmutableList.Builder<>();
      ImmutableList.Builder<String> cellsNamesBuilder = new ImmutableList.Builder<>();
      // TODO(cjhopman): Is this supposed to be to the root cell?
      // Create symlink to our own cell.
      CanonicalCellName thisCellCanonicalName = CanonicalCellName.of(currentCellName);
      AbsPath rootPath = newCellPathResolver.getCellPath(thisCellCanonicalName);
      RelPath relativePath = filesystem.getRootPath().relativize(rootPath);
      cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));
      cellsNamesBuilder.add(Escaper.BASH_ESCAPER.apply(ROOT_CELL_LINK_NAME));

      // Create symlink to the cells.
      for (Map.Entry<Optional<String>, CanonicalCellName> alias :
          cellPathResolver.getCellNameResolver().getKnownCells().entrySet()) {
        if (alias.getKey().isEmpty()) {
          continue;
        }
        AbsPath cellPath = newCellPathResolver.getCellPath(alias.getValue());
        relativePath = filesystem.getRootPath().relativize(cellPath);
        cellsPathsStringsBuilder.add(Escaper.BASH_ESCAPER.apply(relativePath.toString()));

        String cellName = Escaper.BASH_ESCAPER.apply(alias.getKey().get());
        cellsNamesBuilder.add(cellName);
      }

      // Generate an .sh file that builds up an environment and invokes the user's script.
      // This generated .sh file will be returned by getExecutableCommand().
      // This script can be cached and used on machines other than the one where it was
      // created. That means it can't contain any absolute filepaths.

      SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
      ImmutableList.Builder<String> resourceStringsBuilder = new ImmutableList.Builder<>();
      ImmutableSortedSet<AbsPath> resourcesPaths =
          sourcePathResolver.getAllAbsolutePaths(resources.keySet());

      for (AbsPath resourcePath : resourcesPaths) {
        // TODO(cjhopman) This is probably wrong. Shouldn't the resource appear under every cell
        // alias?

        // Get the path relative to its cell.
        RelPath relativeResourcePath =
            newCellPathResolver.getCellPath(buildTarget.getCell()).relativize(resourcePath);
        // Get the path when referenced in the symlink created for the cell in the output folder.
        RelPath linkedResourcePath = RelPath.get(ROOT_CELL_LINK_NAME).resolve(relativeResourcePath);
        resourceStringsBuilder.add(Escaper.BASH_ESCAPER.apply(linkedResourcePath.toString()));
      }

      ImmutableList<String> resourceStrings = resourceStringsBuilder.build();
      RelPath resolvedOutputPath = outputPathResolver.resolvePath(output);
      RelPath pathToProjectRoot =
          resolvedOutputPath.getParent().relativize(filesystem.getBuckPaths().getProjectRootDir());

      // Configure the template output.
      String escapedProjectRoot = Escaper.escapeAsBashString(pathToProjectRoot);
      ImmutableMap.Builder<String, Object> valuesBuilder = ImmutableMap.builder();
      valuesBuilder.put("path_to_project_root_file", escapedProjectRoot);
      valuesBuilder.put(
          "script_to_run",
          Escaper.escapeAsBashString(sourcePathResolver.getRelativePath(filesystem, main)));
      valuesBuilder.put("resources", resourceStrings);
      valuesBuilder.put("cell_names", cellsNamesBuilder.build());
      valuesBuilder.put("cell_paths", cellsPathsStringsBuilder.build());

      // TODO(cjhopman): Why is this so similar to the resource path construction above? What's the
      // difference and why?
      RelPath runtimeRelPath = outputPathResolver.resolvePath(runtimeResourcesDir);
      Path defaultRuntimeResourcesPath =
          runtimeRelPath
              .resolve(ROOT_CELL_LINK_NAME)
              .resolve(
                  buildTarget
                      .getCellRelativeBasePath()
                      .getPath()
                      .toPath(runtimeRelPath.getFileSystem()));

      String defaultRuntimeResources = Escaper.escapeAsBashString(defaultRuntimeResourcesPath);

      valuesBuilder.put("default_runtime_resources", defaultRuntimeResources);

      return ImmutableList.of(
          getSymlinkStep(
              sourcePathResolver,
              filesystem,
              outputPathResolver,
              buildContext.getBuildCellRootPath().getPath()),
          MkdirIsolatedStep.of(resolvedOutputPath.getParent()),
          new StringTemplateStep(
              template,
              TEMPLATE_PATH,
              resolvedOutputPath.getPath(),
              valuesBuilder.build(),
              expanded -> {
                Matcher matcher = CELL_ERROR_MATCHER.matcher(expanded);
                if (matcher.find()) {
                  String matched = matcher.group();
                  LOG.error(
                      "ShBinary template CELL_NAMES expanded to: `%s`, but given `%s`",
                      matched, cellsNamesBuilder.build());
                }
              }),
          new MakeExecutableStep(filesystem, resolvedOutputPath));
    }

    private Step getSymlinkStep(
        SourcePathResolverAdapter resolver,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        Path buildCellRootPath) {
      ImmutableList<String> conflicts = searchForLinkConflicts(resolver, filesystem);
      if (conflicts.isEmpty()) {
        return new SymlinkTreeStep(
            STEP_CATEGORY,
            filesystem,
            buildCellRootPath,
            resources.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        entry ->
                            getSymlinkPath(
                                resolver,
                                filesystem,
                                outputPathResolver,
                                entry.getKey(),
                                entry.getValue()),
                        entry -> resolver.getAbsolutePath(entry.getKey()).getPath())));
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

    private Path getSymlinkPath(
        SourcePathResolverAdapter resolver,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        SourcePath input,
        Optional<String> sourcePathName) {
      Path runtimeLinkPath =
          outputPathResolver
              .resolvePath(runtimeResourcesDir)
              .resolve(deriveLinkPath(resolver, filesystem, input, sourcePathName));
      return filesystem.resolve(runtimeLinkPath);
    }

    private ImmutableList<String> searchForLinkConflicts(
        SourcePathResolverAdapter resolver, ProjectFilesystem filesystem) {
      Map<Path, Path> linkPaths = new HashMap<>();
      ImmutableList.Builder<String> conflicts = ImmutableList.builder();
      for (Map.Entry<SourcePath, Optional<String>> sourcePathEntry : resources.entrySet()) {
        SourcePath sourcePath = sourcePathEntry.getKey();
        Optional<String> sourcePathName = sourcePathEntry.getValue();
        Path linkPath = deriveLinkPath(resolver, filesystem, sourcePath, sourcePathName);
        if (!linkPaths.containsKey(linkPath)) {
          linkPaths.put(linkPath, resolver.getRelativePath(filesystem, sourcePath).getPath());
        } else {
          conflicts.add(
              String.format(
                  "Duplicate resource link path '%s' (Resolves to both '%s' and '%s')",
                  linkPath, sourcePath, linkPaths.get(linkPath)));
        }
      }
      return conflicts.build();
    }

    private Path deriveLinkPath(
        SourcePathResolverAdapter resolver,
        ProjectFilesystem filesystem,
        SourcePath sourcePath,
        Optional<String> sourcePathName) {

      Optional<BuildTarget> targetOptional = getBuildTarget(sourcePath);
      if (targetOptional.isPresent()) {
        BuildTarget target = targetOptional.get();
        if (!target.getCell().equals(buildTarget.getCell())) {
          throw new RuntimeException(
              String.format(
                  "cross-cell resources are not implemented: %s in sh_binary %s",
                  sourcePath, buildTarget));
        }

        // Tack on the target's base path and the source path name of the resource
        return Paths.get(ROOT_CELL_LINK_NAME)
            .resolve(
                target
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPathDefaultFileSystem()
                    .resolve(
                        sourcePathName.orElseGet(
                            () -> resolver.getSourcePathName(target, sourcePath))));
      }

      if (sourcePath instanceof PathSourcePath) {
        return Paths.get(ROOT_CELL_LINK_NAME)
            .resolve(resolver.getRelativePath(filesystem, sourcePath).getPath());
      }

      throw new RuntimeException(
          "unknown source path: "
              + sourcePath
              + "for sh_binary "
              + buildTarget
              + " class: "
              + sourcePath.getClass());
    }
  }

  private static Optional<BuildTarget> getBuildTarget(SourcePath sourcePath) {
    if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
      return Optional.of(((ExplicitBuildTargetSourcePath) sourcePath).getTarget());
    }
    if (sourcePath instanceof DefaultBuildTargetSourcePath) {
      return Optional.of(((DefaultBuildTargetSourcePath) sourcePath).getTarget());
    }
    return Optional.empty();
  }
}
