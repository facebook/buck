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

import com.facebook.buck.cxx.CxxPrepareForLinkStep;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Generate a rustc command line with all appropriate dependencies in place. */
public class RustCompileRule extends AbstractBuildRule implements SupportsInputBasedRuleKey {
  @AddToRuleKey private final Tool compiler;

  @AddToRuleKey private final Linker linker;

  @AddToRuleKey private final ImmutableList<Arg> args;
  @AddToRuleKey private final ImmutableList<Arg> depArgs;
  @AddToRuleKey private final ImmutableList<Arg> linkerArgs;

  @AddToRuleKey private final SourcePath rootModule;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;

  private final Path scratchDir;
  private final String filename;
  @AddToRuleKey private final boolean hasOutput;

  /**
   * Work out how to invoke the Rust compiler, rustc.
   *
   * <p>In Rust, a crate is the equivalent of a package in other languages. It's also the basic unit
   * of compilation.
   *
   * <p>A crate can either be a "binary crate" - which generates an executable - or a "library
   * crate", which makes an .rlib file. .rlib files contain both interface details (function
   * signatures, inline functions, macros, etc) and compiled object code, and so are equivalent to
   * both header files and library archives. There are also dynamic crates which compile to .so
   * files.
   *
   * <p>All crates are compiled from at least one source file, which is its main (or top, or root)
   * module. It may have references to other modules, which may be in other source files. Rustc only
   * needs the main module filename and will find the rest of the source files from there (akin to
   * #include in C/C++). If the crate also has dependencies on other crates, then those .rlib files
   * must also be passed to rustc for the interface details, and to be linked if its a binary crate.
   */
  protected RustCompileRule(
      BuildRuleParams buildRuleParams,
      String filename,
      Tool compiler,
      Linker linker,
      ImmutableList<Arg> args,
      ImmutableList<Arg> depArgs,
      ImmutableList<Arg> linkerArgs,
      ImmutableSortedSet<SourcePath> srcs,
      SourcePath rootModule,
      boolean hasOutput) {
    super(buildRuleParams);

    this.filename = filename;
    this.compiler = compiler;
    this.linker = linker;
    this.args = args;
    this.depArgs = depArgs;
    this.linkerArgs = linkerArgs;
    this.rootModule = rootModule;
    this.srcs = srcs;
    this.scratchDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s-container");
    this.hasOutput = hasOutput;
  }

  public static RustCompileRule from(
      SourcePathRuleFinder ruleFinder,
      BuildRuleParams params,
      String filename,
      Tool compiler,
      Linker linker,
      ImmutableList<Arg> args,
      ImmutableList<Arg> depArgs,
      ImmutableList<Arg> linkerArgs,
      ImmutableSortedSet<SourcePath> sources,
      SourcePath rootModule,
      boolean hasOutput) {
    return new RustCompileRule(
        params.copyReplacingExtraDeps(
            Suppliers.memoize(
                () ->
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(compiler.getDeps(ruleFinder))
                        .addAll(linker.getDeps(ruleFinder))
                        .addAll(
                            Stream.of(args, depArgs, linkerArgs)
                                .flatMap(
                                    a ->
                                        a.stream().flatMap(arg -> arg.getDeps(ruleFinder).stream()))
                                .iterator())
                        .addAll(ruleFinder.filterBuildRuleInputs(ImmutableList.of(rootModule)))
                        .addAll(ruleFinder.filterBuildRuleInputs(sources))
                        .build())),
        filename,
        compiler,
        linker,
        args,
        depArgs,
        linkerArgs,
        sources,
        rootModule,
        hasOutput);
  }

  protected static Path getOutputDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "%s");
  }

  private Path getOutput() {
    return getOutputDir(getBuildTarget(), getProjectFilesystem()).resolve(filename);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {

    Path output = getOutput();

    if (hasOutput) {
      buildableContext.recordArtifact(output);
    }

    SourcePathResolver resolver = buildContext.getSourcePathResolver();

    Path argFilePath =
        getProjectFilesystem()
            .getRootPath()
            .resolve(
                BuildTargets.getScratchPath(
                    getProjectFilesystem(), getBuildTarget(), "%s.argsfile"));
    Path fileListPath =
        getProjectFilesystem()
            .getRootPath()
            .resolve(
                BuildTargets.getScratchPath(
                    getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt"));

    return new ImmutableList.Builder<Step>()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), scratchDir))
        .add(
            new SymlinkFilesIntoDirectoryStep(
                getProjectFilesystem(),
                getProjectFilesystem().getRootPath(),
                srcs.stream()
                    .map(resolver::getRelativePath)
                    .collect(MoreCollectors.toImmutableList()),
                scratchDir))
        .addAll(
            MakeCleanDirectoryStep.of(
                getProjectFilesystem(), getOutputDir(getBuildTarget(), getProjectFilesystem())))
        .addAll(
            CxxPrepareForLinkStep.create(
                argFilePath,
                fileListPath,
                linker.fileList(fileListPath),
                output,
                linkerArgs,
                linker,
                getBuildTarget().getCellPath(),
                resolver))
        .add(
            new ShellStep(getProjectFilesystem().getRootPath()) {

              @Override
              protected ImmutableList<String> getShellCommandInternal(
                  ExecutionContext executionContext) {
                ImmutableList<String> linkerCmd = linker.getCommandPrefix(resolver);
                ImmutableList.Builder<String> cmd = ImmutableList.builder();

                // Accumulate Args into set to dedup them while retaining their order,
                // since there are often many duplicates for things like library paths.
                //
                // NOTE: this means that all logical args should be a single string on the command
                // line (ie "-Lfoo", not ["-L", "foo"])
                ImmutableSet.Builder<String> dedupArgs = ImmutableSet.builder();

                dedupArgs.addAll(Arg.stringify(depArgs, buildContext.getSourcePathResolver()));

                Path src = scratchDir.resolve(resolver.getRelativePath(rootModule));
                cmd.addAll(compiler.getCommandPrefix(resolver));
                if (executionContext.getAnsi().isAnsiTerminal()) {
                  cmd.add("--color=always");
                }
                cmd.add(String.format("-Clinker=%s", linkerCmd.get(0)))
                    .add(String.format("-Clink-arg=@%s", argFilePath))
                    .addAll(Arg.stringify(args, buildContext.getSourcePathResolver()))
                    .addAll(dedupArgs.build())
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
              public ImmutableMap<String, String> getEnvironmentVariables(
                  ExecutionContext context) {
                return compiler.getEnvironment(buildContext.getSourcePathResolver());
              }

              @Override
              public String getShortName() {
                return "rust-build";
              }
            })
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getOutput());
  }

  SourcePath getCrateRoot() {
    return rootModule;
  }
}
