/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rust;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Generate a rustc command line with all appropriate dependencies in place.
 */
public class RustCompileRule
    extends AbstractBuildRuleWithResolver
    implements SupportsInputBasedRuleKey {
  @AddToRuleKey
  private final Tool compiler;

  @AddToRuleKey
  private final Tool linker;

  @AddToRuleKey
  private final ImmutableList<Arg> args;

  @AddToRuleKey
  private final ImmutableList<Arg> linkerArgs;

  @AddToRuleKey
  private final SourcePath rootModule;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;

  private final Path scratchDir;
  private final String filename;

  /**
   * Work out how to invoke the Rust compiler, rustc.
   *
   * In Rust, a crate is the equivalent of a package in other languages. It's also the basic unit of
   * compilation.
   *
   * A crate can either be a "binary crate" - which generates an executable - or a "library crate",
   * which makes an .rlib file. .rlib files contain both interface details (function signatures,
   * inline functions, macros, etc) and compiled object code, and so are equivalent to both header
   * files and library archives. There are also dynamic crates which compile to .so files.
   *
   * All crates are compiled from at least one source file, which is its main (or top, or root)
   * module. It may have references to other modules, which may be in other source files. Rustc only
   * needs the main module filename and will find the rest of the source files from there (akin to
   * #include in C/C++). If the crate also has dependencies on other crates, then those .rlib files
   * must also be passed to rustc for the interface details, and to be linked if its a binary crate.
   */
  protected RustCompileRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      String filename,
      Tool compiler,
      Tool linker,
      ImmutableList<Arg> args,
      ImmutableList<Arg> linkerArgs,
      ImmutableSortedSet<SourcePath> srcs,
      SourcePath rootModule) {
    super(buildRuleParams, resolver);

    this.filename = filename;
    this.compiler = compiler;
    this.linker = linker;
    this.args = args;
    this.linkerArgs = linkerArgs;
    this.rootModule = rootModule;
    this.srcs = srcs;
    this.scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-container");
  }

  public static RustCompileRule from(
      SourcePathRuleFinder ruleFinder,
      BuildRuleParams params,
      SourcePathResolver resolver,
      String filename,
      Tool compiler,
      Tool linker,
      ImmutableList<Arg> args,
      ImmutableList<Arg> linkerArgs,
      ImmutableSortedSet<SourcePath> sources,
      SourcePath rootModule) {
    return new RustCompileRule(
        params.copyWithExtraDeps(
            Suppliers.memoize(
                () -> ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(compiler.getDeps(ruleFinder))
                    .addAll(linker.getDeps(ruleFinder))
                    .addAll(
                        Stream.of(args, linkerArgs)
                            .flatMap(
                                a -> a.stream()
                                    .flatMap(arg -> arg.getDeps(ruleFinder).stream()))
                            .iterator())
                    .addAll(ruleFinder.filterBuildRuleInputs(ImmutableList.of(rootModule)))
                    .addAll(ruleFinder.filterBuildRuleInputs(sources))
                    .build())),
        resolver,
        filename,
        compiler,
        linker,
        args,
        linkerArgs,
        sources,
        rootModule);
  }

  protected static Path getOutputDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "%s");
  }

  private Path getOutput() {
    return getOutputDir(getBuildTarget(), getProjectFilesystem()).resolve(filename);
  }

  // Wrap args for the linker with the appropriate `-C link-arg` or `-C link-args`.
  // Use `link-args` for collections of "simple" args, and `link-arg` for ones containing
  // spaces (or singletons).
  static ImmutableList<String> processLinkerArgs(Iterable<String> linkerArgs) {
    ImmutableList.Builder<ImmutableList<String>> grouper = ImmutableList.builder();
    ImmutableList.Builder<String> accum = ImmutableList.builder();

    for (String arg : linkerArgs) {
      if (!arg.contains(" ")) {
        accum.add(arg);
      } else {
        grouper.add(accum.build());
        accum = ImmutableList.builder();
        grouper.add(ImmutableList.of(arg));
      }
    }
    grouper.add(accum.build());

    return grouper.build().stream()
        .filter(g -> g.size() > 0)
        .map(
            g -> {
              if (g.size() == 1) {
                return String.format("-Clink-arg=%s", g.get(0));
              } else {
                return String.format("-Clink-args=%s", String.join(" ", g));
              }
            })
        .collect(MoreCollectors.toImmutableList());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    Path output = getOutput();

    buildableContext.recordArtifact(output);

    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        new SymlinkFilesIntoDirectoryStep(
            getProjectFilesystem(),
            getProjectFilesystem().getRootPath(),
            srcs.stream()
                .map(getResolver()::getRelativePath)
                .collect(MoreCollectors.toImmutableList()),
            scratchDir),
        new MakeCleanDirectoryStep(
            getProjectFilesystem(),
            getOutputDir(getBuildTarget(), getProjectFilesystem())),
        new ShellStep(getProjectFilesystem().getRootPath()) {

          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            ImmutableList<String> linkerCmd = linker.getCommandPrefix(getResolver());
            ImmutableList.Builder<String> cmd = ImmutableList.builder();

            Path src = scratchDir.resolve(getResolver().getRelativePath(rootModule));

            cmd
                .addAll(compiler.getCommandPrefix(getResolver()))
                .addAll(
                    context.getAnsi().isAnsiTerminal() ?
                        ImmutableList.of("--color=always") : ImmutableList.of())
                .add(String.format("-Clinker=%s", linkerCmd.get(0)))
                .addAll(processLinkerArgs(linkerCmd.subList(1, linkerCmd.size())))
                .addAll(processLinkerArgs(Arg.stringify(linkerArgs)))
                .addAll(Arg.stringify(args))
                .add("-o", output.toString())
                .add(src.toString());

            return cmd.build();
          }

          /*
           * Make sure all stderr output from rustc is emitted, since its either a warning or an
           * error. In general Rust code should have zero warnings, or all warnings as errors.
           * Regardless, respect requests for silence.
           */
          @Override
          protected boolean shouldPrintStderr(Verbosity verbosity) {
            return !verbosity.isSilent();
          }

          @Override
          public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
            return compiler.getEnvironment();
          }

          @Override
          public String getShortName() {
            return "rust-build";
          }

        });
  }

  @Override
  public Path getPathToOutput() {
    return getOutput();
  }

  SourcePath getCrateRoot() {
    return rootModule;
  }
}
