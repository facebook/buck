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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.ByteSource;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * A {@link SymbolNameTool} implementation using a POSIX-compliant `nm` utility
 * (http://pubs.opengroup.org/onlinepubs/009696699/utilities/nm.html).
 */
public class PosixNmSymbolNameTool implements SymbolNameTool {

  private final Tool nm;

  public PosixNmSymbolNameTool(Tool nm) {
    this.nm = nm;
  }

  @Override
  public SourcePath createUndefinedSymbolsFile(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      Iterable<? extends SourcePath> linkerInputs) {
    UndefinedSymbolsFile rule =
        ruleResolver.addToIndex(
            new UndefinedSymbolsFile(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(BuildableSupport.getDepsCollection(nm, ruleFinder))
                            .addAll(ruleFinder.filterBuildRuleInputs(linkerInputs))
                            .build())
                    .withoutExtraDeps(),
                nm,
                linkerInputs));
    return rule.getSourcePathToOutput();
  }

  private static class UndefinedSymbolsFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final Tool nm;

    @AddToRuleKey private final Iterable<? extends SourcePath> inputs;

    public UndefinedSymbolsFile(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Tool nm,
        Iterable<? extends SourcePath> inputs) {
      super(buildTarget, projectFilesystem, buildRuleParams);
      this.nm = nm;
      this.inputs = inputs;
    }

    private Path getUndefinedSymbolsPath() {
      return BuildTargets.getGenPath(
          getProjectFilesystem(), getBuildTarget(), "%s/undefined_symbols.txt");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      Path output = getUndefinedSymbolsPath();

      // Cache the symbols file.
      buildableContext.recordArtifact(output);

      // Run `nm` on the inputs.
      ShellStep shellStep =
          new DefaultShellStep(
              getBuildTarget(),
              getProjectFilesystem().getRootPath(),
              ImmutableList.<String>builder()
                  .addAll(nm.getCommandPrefix(context.getSourcePathResolver()))
                  // Prepend all lines with the name of the input file to which it
                  // corresponds.  Added only to make parsing the output a bit easier.
                  .add("-A")
                  // Generate output in a portable output format.
                  .add("-P")
                  // Only list external symbols.
                  .add("-g")
                  // Only list undefined symbols.
                  .add("-u")
                  .addAll(
                      StreamSupport.stream(inputs.spliterator(), false)
                          .map(context.getSourcePathResolver()::getAbsolutePath)
                          .map(Object::toString)
                          .iterator())
                  .build(),
              nm.getEnvironment(context.getSourcePathResolver())) {
            @Override
            protected void addOptions(ImmutableSet.Builder<ProcessExecutor.Option> options) {
              options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
            }
          };

      // Parse the output from running `nm` and write all symbols to the symbol file.
      MkdirStep mkdirStep =
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent()));
      WriteFileStep writeFileStep =
          new WriteFileStep(
              getProjectFilesystem(),
              new ByteSource() {
                @Override
                public InputStream openStream() throws IOException {
                  Set<String> symbols = new LinkedHashSet<>();
                  Pattern pattern = Pattern.compile("^\\S+: (?<name>\\S+) .*");
                  try (BufferedReader reader =
                      new BufferedReader(new StringReader(shellStep.getStdout()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                      Matcher matcher = pattern.matcher(line);
                      if (matcher.matches()) {
                        symbols.add(matcher.group("name"));
                      }
                    }
                  }
                  StringBuilder builder = new StringBuilder();
                  for (String symbol : symbols) {
                    builder.append(symbol);
                    builder.append(System.lineSeparator());
                  }
                  return new ByteArrayInputStream(builder.toString().getBytes(Charsets.UTF_8));
                }
              },
              output,
              /* executable */ true);

      return ImmutableList.of(shellStep, mkdirStep, writeFileStep);
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getUndefinedSymbolsPath());
    }
  }
}
