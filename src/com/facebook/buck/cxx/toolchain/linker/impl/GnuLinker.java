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
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.PathWrapper;
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
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.collect.Streams;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** A specialization of {@link Linker} containing information specific to the GNU implementation. */
public class GnuLinker extends DelegatingTool implements Linker, HasIncrementalThinLTO {
  public GnuLinker(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(
      ImmutableMap<Path, Path> cellRootMap, Optional<ImmutableSet<AbsPath>> focusedTargetsPaths) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input, SourcePathResolverAdapter resolver) {
    return ImmutableList.of(
        StringArg.of("-Wl,--whole-archive"), input, StringArg.of("-Wl,--no-whole-archive"));
  }

  @Override
  public Iterable<Arg> asLibrary(Iterable<Arg> objects) {
    return ImmutableList.<Arg>builder()
        .add(StringArg.of("-Wl,--start-lib"))
        .addAll(objects)
        .add(StringArg.of("-Wl,--end-lib"))
        .build();
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

    // In many cases, symbols are mostly the same across different top-level targets, so intern them
    // to save memory.
    private static final Interner<String> SYMBOL_INTERNER = Interners.newWeakInterner();

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

    @SuppressWarnings("unused")
    void writePreamble(BufferedWriter writer) throws IOException {}

    @SuppressWarnings("unused")
    void writePostamble(BufferedWriter writer) throws IOException {}

    abstract void writeSymbol(Writer writer, String symbol) throws IOException;

    private RelPath getScript() {
      String format = String.format("%%s/%s_script.txt", getScriptType());
      return BuildTargetPaths.getGenPath(
          getProjectFilesystem().getBuckPaths(), getBuildTarget(), format);
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
          new IsolatedStep() {

            private final Path output = getProjectFilesystem().resolve(linkerScript).getPath();
            private final ImmutableList<Path> inputs =
                Streams.stream(symbolFiles)
                    .map(context.getSourcePathResolver()::getAbsolutePath)
                    .map(PathWrapper::getPath)
                    .collect(ImmutableList.toImmutableList());

            @Override
            public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
                throws IOException {
              try (BufferedWriter writer =
                  new BufferedWriter(
                      new OutputStreamWriter(
                          Files.newOutputStream(output), StandardCharsets.UTF_8))) {
                writePreamble(writer);

                Set<String> symbols = new HashSet<>();
                for (Path input : inputs) {
                  try (BufferedReader reader = Files.newBufferedReader(input)) {
                    String symbol;
                    while ((symbol = reader.readLine()) != null) {
                      symbol = SYMBOL_INTERNER.intern(symbol);
                      if (symbols.add(symbol)) {
                        writeSymbol(writer, symbol);
                        writer.newLine();
                      }
                    }
                  }
                }

                for (String symbol : extraSymbols) {
                  if (symbols.add(symbol)) {
                    writeSymbol(writer, symbol);
                    writer.newLine();
                  }
                }

                writePostamble(writer);
              }
              return StepExecutionResults.SUCCESS;
            }

            @Override
            public String getIsolatedStepDescription(IsolatedExecutionContext context) {
              return String.format("Generate %s script for symbol lists", getScriptType());
            }

            @Override
            public String getShortName() {
              return String.format("write-%s-script", getScriptType());
            }
          });
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
    void writeSymbol(Writer writer, String symbol) throws IOException {
      writer.write("EXTERN(\"");
      writer.write(symbol);
      writer.write("\")");
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
    void writePreamble(BufferedWriter writer) throws IOException {
      writer.write("{");
      writer.newLine();
      writer.write("  global:");
      writer.newLine();
    }

    @Override
    void writePostamble(BufferedWriter writer) throws IOException {
      writer.write("  local: *;");
      writer.newLine();
      writer.write("};");
      writer.newLine();
    }

    @Override
    void writeSymbol(Writer writer, String symbol) throws IOException {
      writer.write("  \"");
      writer.write(symbol);
      writer.write("\";");
    }
  }
}
