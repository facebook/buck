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

package com.facebook.buck.cxx.toolchain.linker.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.linker.HasIncrementalThinLTO;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A specialization of {@link Linker} containing information specific to the GNU implementation. */
public class GnuLinker extends DelegatingTool implements Linker, HasIncrementalThinLTO {
  public GnuLinker(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(
      ImmutableMap<Path, Path> cellRootMap, ImmutableSet<AbsPath> focusedTargetsPaths) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input, SourcePathResolverAdapter resolver) {
    return ImmutableList.of(
        StringArg.of("-Wl,--whole-archive"), input, StringArg.of("-Wl,--no-whole-archive"));
  }

  @Override
  public Iterable<Arg> incrementalThinLTOFlags(Path output) {
    return StringArg.from(
        "-Wl,-plugin-opt,thinlto-index-only=thinlto.objects",
        "-Wl,-plugin-opt,thinlto-emit-imports-files",
        "-Xlinker",
        "-plugin-opt",
        "-Xlinker",
        "thinlto-prefix-replace=;" + output.toString());
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
   * Write all global symbols given in {@code symbolFiles} into a version script under the `global`
   * section.
   *
   * @param target the name given to the {@link BuildRule} which creates the version script.
   * @param symbolFiles
   * @param extraGlobals
   * @return the list of arguments which pass the version script containing the global symbols to
   *     link.
   */
  @Override
  public ImmutableList<Arg> createGlobalSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles,
      ImmutableList<String> extraGlobals) {
    GlobalSymbolsVersionScript rule =
        graphBuilder.addToIndex(
            new GlobalSymbolsVersionScript(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.copyOf(graphBuilder.filterBuildRuleInputs(symbolFiles)))
                    .withoutExtraDeps(),
                symbolFiles,
                extraGlobals));
    return ImmutableList.of(
        StringArg.of("-Wl,--version-script"), SourcePathArg.of(rule.getSourcePathToOutput()));
  }

  @Override
  public String getExportDynamicSymbolFlag() {
    return "--export-dynamic-symbol";
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
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles) {
    UndefinedSymbolsLinkerScript rule =
        graphBuilder.addToIndex(
            new UndefinedSymbolsLinkerScript(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.copyOf(graphBuilder.filterBuildRuleInputs(symbolFiles)))
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
    return ImmutableList.of("-o", PathFormatter.pathWithUnixSeparators(path));
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

  private abstract static class SymbolsScript extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final Iterable<? extends SourcePath> symbolFiles;
    @AddToRuleKey private final ImmutableList<String> extraSymbols;

    public SymbolsScript(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Iterable<? extends SourcePath> symbolFiles,
        ImmutableList<String> extraSymbols) {
      super(buildTarget, projectFilesystem, buildRuleParams);
      this.symbolFiles = symbolFiles;
      this.extraSymbols = extraSymbols;
    }

    abstract String getScriptType();

    abstract List<String> mapSymbols(Set<String> symbols);

    private RelPath getScript() {
      String format = String.format("%%s/%s_script.txt", getScriptType());
      return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), format);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      RelPath linkerScript = getScript();
      buildableContext.recordArtifact(linkerScript.getPath());
      return ImmutableList.of(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  linkerScript.getParent())),
          WriteFileStep.of(
              getProjectFilesystem().getRootPath(),
              () -> {
                Set<String> symbols = new LinkedHashSet<>();
                for (SourcePath path : symbolFiles) {
                  try {
                    symbols.addAll(
                        Files.readAllLines(
                            context.getSourcePathResolver().getAbsolutePath(path).getPath(),
                            StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
                symbols.addAll(extraSymbols);
                List<String> lines = mapSymbols(symbols);
                return Joiner.on(System.lineSeparator()).join(lines);
              },
              linkerScript.getPath(),
              /* executable */ false));
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getScript());
    }
  }

  // Write all symbols to a linker script, using the `EXTERN` command to mark them as undefined
  // symbols.
  private static class UndefinedSymbolsLinkerScript extends SymbolsScript {

    public UndefinedSymbolsLinkerScript(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Iterable<? extends SourcePath> symbolFiles) {
      super(buildTarget, projectFilesystem, buildRuleParams, symbolFiles, ImmutableList.of());
    }

    @Override
    String getScriptType() {
      return "linker";
    }

    @Override
    List<String> mapSymbols(Set<String> symbols) {
      List<String> mapped = new ArrayList<>();
      for (String symbol : symbols) {
        mapped.add(String.format("EXTERN(\"%s\")", symbol));
      }
      return mapped;
    }
  }

  // Write all symbols to a version script, under the `global` section to mark them as exported
  // symbols.
  private static class GlobalSymbolsVersionScript extends SymbolsScript {

    public GlobalSymbolsVersionScript(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Iterable<? extends SourcePath> symbolFiles,
        ImmutableList<String> extraGlobals) {
      super(buildTarget, projectFilesystem, buildRuleParams, symbolFiles, extraGlobals);
    }

    @Override
    String getScriptType() {
      return "version";
    }

    @Override
    List<String> mapSymbols(Set<String> symbols) {
      List<String> mapped = new ArrayList<>();
      mapped.add("{");
      mapped.add("  global:");
      for (String symbol : symbols) {
        mapped.add(String.format("  \"%s\";", symbol));
      }
      mapped.add("  local: *;");
      mapped.add("};");
      return mapped;
    }
  }
}
