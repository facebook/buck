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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A specialization of {@link Linker} containing information specific to the GNU implementation. */
public class GnuLinker implements Linker {

  private final Tool tool;

  public GnuLinker(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return tool.getDeps(ruleFinder);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return tool.getEnvironment(resolver);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(ImmutableCollection<Path> cellRoots) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input) {
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
   * @return the list of arguments which pass the linker script containing the undefined symbols to
   *     link.
   */
  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Iterable<? extends SourcePath> symbolFiles) {
    UndefinedSymbolsLinkerScript rule =
        ruleResolver.addToIndex(
            new UndefinedSymbolsLinkerScript(
                baseParams
                    .withBuildTarget(target)
                    .copyReplacingDeclaredAndExtraDeps(
                        Suppliers.ofInstance(
                            ImmutableSortedSet.copyOf(
                                ruleFinder.filterBuildRuleInputs(symbolFiles))),
                        Suppliers.ofInstance(ImmutableSortedSet.of())),
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
    return ImmutableList.of("-o", path);
  }

  @Override
  public boolean hasFilePathSizeLimitations() {
    return false;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("tool", tool).setReflectively("type", getClass().getSimpleName());
  }

  // Write all symbols to a linker script, using the `EXTERN` command to mark them as undefined
  // symbols.
  private static class UndefinedSymbolsLinkerScript extends AbstractBuildRule {

    @AddToRuleKey private final Iterable<? extends SourcePath> symbolFiles;

    public UndefinedSymbolsLinkerScript(
        BuildRuleParams buildRuleParams, Iterable<? extends SourcePath> symbolFiles) {
      super(buildRuleParams);
      this.symbolFiles = symbolFiles;
    }

    private Path getLinkerScript() {
      return BuildTargets.getGenPath(
          getProjectFilesystem(), getBuildTarget(), "%s/linker_script.txt");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      final Path linkerScript = getLinkerScript();
      buildableContext.recordArtifact(linkerScript);
      return ImmutableList.of(
          MkdirStep.of(getProjectFilesystem(), linkerScript.getParent()),
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
      return new ExplicitBuildTargetSourcePath(getBuildTarget(), getLinkerScript());
    }
  }
}
