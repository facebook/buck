/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain.linker;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A specialization of {@link Linker} containing information specific to the GNU implementation. */
public class GnuLinker extends DelegatingTool implements Linker {
  public GnuLinker(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(ImmutableMap<Path, Path> cellRootMap) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input, SourcePathResolver resolver) {
    return ImmutableList.of(
        StringArg.of("-Wl,--whole-archive"), input, StringArg.of("-Wl,--no-whole-archive"));
  }

  @Override
  public Iterable<String> soname(String arg) {
    return Linkers.iXlinker("-soname", arg);
  }

  @Override
  public Iterable<Arg> fileList(Path fileListPath) {
    return ImmutableList.of();
  }

  @Override
  public String origin() {
    return "$ORIGIN";
  }

  @Override
  public String libOrigin() {
    return "$ORIGIN";
  }

  @Override
  public String searchPathEnvVar() {
    return "LD_LIBRARY_PATH";
  }

  @Override
  public String preloadEnvVar() {
    return "LD_PRELOAD";
  }

  /**
   * Write all undefined symbols given in {@code symbolFiles} into a linker script wrapped in
   * `EXTERN` commands.
   *
   * @param target the name given to the {@link BuildRule} which creates the linker script.
   * @param symbolFiles
   * @return the list of arguments which pass the linker script containing the undefined symbols to
   *     link.
   */
  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles) {
    UndefinedSymbolsLinkerScript rule =
        graphBuilder.addToIndex(
            new UndefinedSymbolsLinkerScript(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(symbolFiles)))
                    .withoutExtraDeps(),
                symbolFiles));
    return ImmutableList.of(SourcePathArg.of(rule.getSourcePathToOutput()));
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return Linkers.iXlinker("--no-as-needed");
  }

  @Override
  public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
    return Linkers.iXlinker(
        // ld.gold doesn't appear to fully implement `--unresolved-symbols=ignore-all` for shared
        // libraries, so also always set `--allow-shlib-undefined`.
        "--allow-shlib-undefined", "--unresolved-symbols=ignore-all");
  }

  @Override
  public Iterable<Arg> getSharedLibFlag() {
    return ImmutableList.of(StringArg.of("-shared"));
  }

  @Override
  public Iterable<String> outputArgs(String path) {
    return ImmutableList.of("-o", MorePaths.pathWithUnixSeparators(path));
  }

  @Override
  public SharedLibraryLoadingType getSharedLibraryLoadingType() {
    return SharedLibraryLoadingType.RPATH;
  }

  @Override
  public Optional<ExtraOutputsDeriver> getExtraOutputsDeriver() {
    return Optional.empty();
  }

  @Override
  public boolean getUseUnixPathSeparator() {
    return true;
  }

  // Write all symbols to a linker script, using the `EXTERN` command to mark them as undefined
  // symbols.
  private static class UndefinedSymbolsLinkerScript
      extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final Iterable<? extends SourcePath> symbolFiles;

    public UndefinedSymbolsLinkerScript(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Iterable<? extends SourcePath> symbolFiles) {
      super(buildTarget, projectFilesystem, buildRuleParams);
      this.symbolFiles = symbolFiles;
    }

    private Path getLinkerScript() {
      return BuildTargetPaths.getGenPath(
          getProjectFilesystem(), getBuildTarget(), "%s/linker_script.txt");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      Path linkerScript = getLinkerScript();
      buildableContext.recordArtifact(linkerScript);
      return ImmutableList.of(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  linkerScript.getParent())),
          new WriteFileStep(
              getProjectFilesystem(),
              () -> {
                Set<String> symbols = new LinkedHashSet<>();
                for (SourcePath path : symbolFiles) {
                  try {
                    symbols.addAll(
                        Files.readAllLines(
                            context.getSourcePathResolver().getAbsolutePath(path), Charsets.UTF_8));
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
                List<String> lines = new ArrayList<>();
                for (String symbol : symbols) {
                  lines.add(String.format("EXTERN(\"%s\")", symbol));
                }
                return Joiner.on(System.lineSeparator()).join(lines);
              },
              linkerScript,
              /* executable */ false));
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLinkerScript());
    }
  }
}
