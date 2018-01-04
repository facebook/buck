/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.android.relinker;

import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.OverrideScheduleRule;
import com.facebook.buck.rules.RuleScheduleInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

class RelinkerRule extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements OverrideScheduleRule {

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> symbolsNeededPaths;
  @AddToRuleKey private final TargetCpuType cpuType;
  @AddToRuleKey private final SourcePath baseLibSourcePath;
  @AddToRuleKey private final Tool objdump;
  @AddToRuleKey private final ImmutableList<Arg> linkerArgs;
  @AddToRuleKey @Nullable private final Linker linker;

  @AddToRuleKey(stringify = true)
  private final ImmutableList<Pattern> symbolWhitelist;

  private final BuildRuleParams buildRuleParams;
  private final CxxBuckConfig cxxBuckConfig;
  private final SourcePathResolver pathResolver;
  private final CellPathResolver cellPathResolver;

  public RelinkerRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      CellPathResolver cellPathResolver,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<SourcePath> symbolsNeededPaths,
      TargetCpuType cpuType,
      Tool objdump,
      CxxBuckConfig cxxBuckConfig,
      SourcePath baseLibSourcePath,
      @Nullable Linker linker,
      ImmutableList<Arg> linkerArgs,
      ImmutableList<Pattern> symbolWhitelist) {
    super(
        buildTarget, projectFilesystem, withDepsFromArgs(buildRuleParams, ruleFinder, linkerArgs));
    this.pathResolver = resolver;
    this.cellPathResolver = cellPathResolver;
    this.cpuType = cpuType;
    this.objdump = objdump;
    this.cxxBuckConfig = cxxBuckConfig;
    this.linkerArgs = linkerArgs;
    this.buildRuleParams = buildRuleParams;
    this.symbolsNeededPaths = symbolsNeededPaths;
    this.baseLibSourcePath = baseLibSourcePath;
    this.linker = linker;
    this.symbolWhitelist = symbolWhitelist;
  }

  private static BuildRuleParams withDepsFromArgs(
      BuildRuleParams params, SourcePathRuleFinder ruleFinder, ImmutableList<Arg> args) {
    return params.copyAppendingExtraDeps(
        Iterables.concat(
            Iterables.transform(args, arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))));
  }

  private static String getVersionScript(
      Set<String> needed, Set<String> provided, List<Pattern> whitelist) {
    Set<String> keep =
        new ImmutableSet.Builder<String>()
            .addAll(Sets.intersection(needed, provided))
            .addAll(
                Sets.filter(
                    provided,
                    s -> {
                      if (s.contains("JNI_OnLoad")) {
                        return true;
                      }
                      if (s.contains("Java_")) {
                        return true;
                      }
                      for (Pattern pattern : whitelist) {
                        if (pattern.matcher(s).matches()) {
                          return true;
                        }
                      }
                      return false;
                    }))
            .build();
    String res = "{\n";
    if (!keep.isEmpty()) {
      res += "global:\n";
    }
    for (String s : keep) {
      res += "  " + s + ";\n";
    }
    res += "local: *;\n};\n";
    return res;
  }

  public SourcePath getLibFileSourcePath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibFilePath());
  }

  public SourcePath getSymbolsNeededPath() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getSymbolsNeededOutPath());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) {

    final ImmutableList.Builder<Step> relinkerSteps = ImmutableList.builder();
    if (linker != null) {
      ImmutableList<Arg> args =
          ImmutableList.<Arg>builder()
              .addAll(linkerArgs)
              .add(StringArg.of("-Wl,--version-script=" + getRelativeVersionFilePath().toString()))
              .build();

      relinkerSteps.addAll(
          new CxxLink(
                  getBuildTarget()
                      .withAppendedFlavors(InternalFlavor.of("cxx-link"))
                      .withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor()),
                  getProjectFilesystem(),
                  buildRuleParams::getBuildDeps,
                  cellPathResolver,
                  linker,
                  getLibFilePath(),
                  ImmutableMap.of(),
                  args,
                  Optional.empty(),
                  cxxBuckConfig.getLinkScheduleInfo(),
                  cxxBuckConfig.shouldCacheLinks(),
                  /* thinLto */ false)
              .getBuildSteps(context, buildableContext));
      buildableContext.recordArtifact(getRelativeVersionFilePath());
    }

    buildableContext.recordArtifact(getSymbolsNeededOutPath());

    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), getScratchDirPath())))
        .add(
            new AbstractExecutionStep("xdso-dce relinker") {
              @Override
              public StepExecutionResult execute(ExecutionContext context)
                  throws IOException, InterruptedException {
                ImmutableSet<String> symbolsNeeded = readSymbolsNeeded();
                if (linker == null) {
                  getProjectFilesystem().copyFile(getBaseLibPath(), getLibFilePath());
                  buildableContext.recordArtifact(getLibFilePath());
                } else {
                  writeVersionScript(context.getProcessExecutor(), symbolsNeeded);
                  for (Step s : relinkerSteps.build()) {
                    StepExecutionResult executionResult = s.execute(context);
                    if (!executionResult.isSuccess()) {
                      return StepExecutionResults.ERROR;
                    }
                  }
                }
                writeSymbols(
                    getSymbolsNeededOutPath(),
                    Sets.union(
                        symbolsNeeded,
                        getSymbols(context.getProcessExecutor(), getLibFilePath()).undefined));
                return StepExecutionResults.SUCCESS;
              }
            })
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibFilePath());
  }

  @Override
  public RuleScheduleInfo getRuleScheduleInfo() {
    return cxxBuckConfig.getLinkScheduleInfo().orElse(RuleScheduleInfo.DEFAULT);
  }

  private Path getScratchPath() {
    // ld doesn't seem to like commas in the version script path so we construct one without commas.
    Path path = BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
    String dirname = path.getFileName().toString().replace(',', '.');
    return path.getParent().resolve(dirname);
  }

  private Path getBaseLibPath() {
    return pathResolver.getAbsolutePath(baseLibSourcePath);
  }

  private Path getScratchDirPath() {
    return getScratchPath().resolve(cpuType.toString());
  }

  private Path getScratchFilePath(String suffix) {
    return getScratchDirPath()
        .resolve(MorePaths.getNameWithoutExtension(getBaseLibPath()) + suffix);
  }

  private Path getLibFilePath() {
    return getScratchDirPath().resolve(getBaseLibPath().getFileName());
  }

  private Symbols getSymbols(ProcessExecutor executor, Path path)
      throws IOException, InterruptedException {
    return Symbols.getDynamicSymbols(executor, objdump, pathResolver, absolutify(path));
  }

  private Path getRelativeVersionFilePath() {
    return getScratchFilePath("__version.exp");
  }

  private void writeVersionScript(ProcessExecutor executor, ImmutableSet<String> symbolsNeeded)
      throws IOException, InterruptedException {
    Symbols sym = getSymbols(executor, getBaseLibPath());
    Set<String> defined = Sets.difference(sym.all, sym.undefined);
    String versionScript = getVersionScript(symbolsNeeded, defined, symbolWhitelist);

    Files.write(
        absolutify(getRelativeVersionFilePath()),
        versionScript.getBytes(Charsets.UTF_8),
        StandardOpenOption.CREATE);
  }

  private Path absolutify(Path p) {
    return getProjectFilesystem().resolve(p);
  }

  private Path getSymbolsNeededOutPath() {
    return getScratchFilePath(".symbols");
  }

  private void writeSymbols(Path dest, Set<String> symbols) throws IOException {
    Files.write(
        absolutify(dest),
        ImmutableSortedSet.copyOf(symbols),
        Charsets.UTF_8,
        StandardOpenOption.CREATE);
  }

  private ImmutableSet<String> readSymbolsNeeded() throws IOException {
    ImmutableSet.Builder<String> symbolsNeeded = ImmutableSet.builder();
    for (SourcePath source : symbolsNeededPaths) {
      symbolsNeeded.addAll(
          Files.readAllLines(pathResolver.getAbsolutePath(source), Charsets.UTF_8));
    }
    return symbolsNeeded.build();
  }
}
