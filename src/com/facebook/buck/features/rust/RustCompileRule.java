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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxPrepareForLinkStep;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.features.rust.RustBuckConfig.RemapSrcPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Optional;

/** Generate a rustc command line with all appropriate dependencies in place. */
public class RustCompileRule extends ModernBuildRule<RustCompileRule.Impl> {

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
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      String filename,
      Tool compiler,
      Linker linker,
      ImmutableList<Arg> args,
      ImmutableList<Arg> depArgs,
      ImmutableList<Arg> linkerArgs,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableSortedMap<SourcePath, Optional<String>> mappedSources,
      String rootModule,
      RemapSrcPaths remapSrcPaths,
      Optional<String> xcrunSdkPath) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            compiler,
            linker,
            buildTarget,
            args,
            depArgs,
            linkerArgs,
            environment,
            rootModule,
            filename,
            mappedSources,
            remapSrcPaths,
            xcrunSdkPath));
  }

  public static RustCompileRule from(
      SourcePathRuleFinder ruleFinder,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      String filename,
      Tool compiler,
      Linker linker,
      ImmutableList<Arg> args,
      ImmutableList<Arg> depArgs,
      ImmutableList<Arg> linkerArgs,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableSortedMap<SourcePath, Optional<String>> mappedSources,
      String rootModule,
      RemapSrcPaths remapSrcPaths,
      Optional<String> xcrunSdkPath) {
    return new RustCompileRule(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        filename,
        compiler,
        linker,
        args,
        depArgs,
        linkerArgs,
        environment,
        mappedSources,
        rootModule,
        remapSrcPaths,
        xcrunSdkPath);
  }

  protected static Path getOutputDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, target, "%s");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  String getCrateRoot() {
    return getBuildable().rootModule;
  }

  /** internal buildable implementation */
  static class Impl implements Buildable {
    @AddToRuleKey private final Tool compiler;

    @AddToRuleKey private final Linker linker;

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final ImmutableList<Arg> args;
    @AddToRuleKey private final ImmutableList<Arg> depArgs;
    @AddToRuleKey private final ImmutableList<Arg> linkerArgs;
    @AddToRuleKey private final ImmutableSortedMap<String, Arg> environment;

    @AddToRuleKey private final String rootModule;
    @AddToRuleKey private final OutputPath output;

    @AddToRuleKey private final ImmutableSortedMap<SourcePath, Optional<String>> mappedSources;

    @AddToRuleKey private final RustBuckConfig.RemapSrcPaths remapSrcPaths;

    @ExcludeFromRuleKey(
        reason = "This should probably be properly represented as a ToolChain?",
        serialization = DefaultFieldSerialization.class,
        inputs = IgnoredFieldInputs.class)
    private final Optional<String> xcrunSdkpath;

    public Impl(
        Tool compiler,
        Linker linker,
        BuildTarget buildTarget,
        ImmutableList<Arg> args,
        ImmutableList<Arg> depArgs,
        ImmutableList<Arg> linkerArgs,
        ImmutableSortedMap<String, Arg> environment,
        String rootModule,
        String outputName,
        ImmutableSortedMap<SourcePath, Optional<String>> mappedSources,
        RemapSrcPaths remapSrcPaths,
        Optional<String> xcrunpath) {
      this.compiler = compiler;
      this.linker = linker;
      this.buildTarget = buildTarget;
      this.args = args;
      this.depArgs = depArgs;
      this.linkerArgs = linkerArgs;
      this.environment = environment;
      this.rootModule = rootModule;
      this.output = new OutputPath(outputName);
      this.mappedSources = mappedSources;
      this.remapSrcPaths = remapSrcPaths;
      this.xcrunSdkpath = xcrunpath;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path outputdir = getOutputDir(buildTarget, filesystem);
      Path outputPath = outputPathResolver.resolvePath(output);
      Path scratchDir = outputPathResolver.getTempPath();

      SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();

      AbsPath argFilePath =
          filesystem.getRootPath().resolve(outputPathResolver.getTempPath("argsfile.txt"));
      AbsPath fileListPath =
          filesystem.getRootPath().resolve(outputPathResolver.getTempPath("filelist.txt"));

      ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
      steps.add(
          new SymlinkTreeStep(
              "rust_sources",
              filesystem,
              scratchDir,
              mappedSources.entrySet().stream()
                  .collect(
                      ImmutableMap.toImmutableMap(
                          ent -> {
                            Path path;
                            if (ent.getValue().isPresent()) {
                              path =
                                  buildTarget
                                      .getCellRelativeBasePath()
                                      .getPath()
                                      .toPath(filesystem.getFileSystem())
                                      .resolve(ent.getValue().get());
                            } else {
                              path = resolver.getRelativePath(ent.getKey());
                            }
                            return path;
                          },
                          ent -> resolver.getAbsolutePath(ent.getKey())))));
      steps.addAll(
          CxxPrepareForLinkStep.create(
              argFilePath.getPath(),
              fileListPath.getPath(),
              linker.fileList(fileListPath),
              outputPath,
              linkerArgs,
              linker,
              buildTarget.getCell(),
              filesystem.getRootPath().getPath(),
              resolver));

      steps.add(
          new ShellStep(filesystem.getRootPath()) {

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

              Path src = scratchDir.resolve(rootModule);
              cmd.addAll(compiler.getCommandPrefix(resolver));
              if (executionContext.getAnsi().isAnsiTerminal()) {
                cmd.add("--color=always");
              }

              remapSrcPaths.addRemapOption(cmd, workingDirectory.toString(), scratchDir + "/");

              // Generate a target-unique string to distinguish distinct crates with the same
              // name.
              String metadata = RustCompileUtils.hashForTarget(buildTarget);

              cmd.add(String.format("-Clinker=%s", linkerCmd.get(0)))
                  .addAll(
                      linkerCmd.subList(1, linkerCmd.size()).stream()
                          .map(l -> String.format("-Clink-arg=%s", l))
                          .iterator())
                  .add(String.format("-Clink-arg=@%s", argFilePath))
                  .add(String.format("-Cmetadata=%s", metadata))
                  .add(String.format("-Cextra-filename=-%s", metadata))
                  .addAll(Arg.stringify(args, buildContext.getSourcePathResolver()))
                  .addAll(dedupArgs.build())
                  .add("--out-dir", outputdir.toString())
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
              ImmutableMap.Builder<String, String> env = ImmutableMap.builder();
              env.putAll(compiler.getEnvironment(buildContext.getSourcePathResolver()));
              env.putAll(
                  Maps.transformValues(
                      environment, v -> Arg.stringify(v, buildContext.getSourcePathResolver())));

              AbsPath root = filesystem.getRootPath();
              ForwardRelativePath basePath = buildTarget.getCellRelativeBasePath().getPath();

              // These need to be set as absolute paths - the intended use
              // is within an `include!(concat!(env!("..."), "...")`
              // invocation in Rust source, and if the path isn't absolute
              // it will be treated as relative to the current file including
              // it. The trailing '/' is also to assist this use-case.
              env.put("RUSTC_BUILD_CONTAINER", root.resolve(scratchDir) + "/");
              env.put(
                  "RUSTC_BUILD_CONTAINER_BASE_PATH",
                  root.resolve(scratchDir.resolve(basePath.toPath(scratchDir.getFileSystem())))
                      + "/");
              Impl.this.xcrunSdkpath.ifPresent((path) -> env.put("SDKROOT", path));
              return env.build();
            }

            @Override
            public String getShortName() {
              return "rust-build";
            }
          });

      return steps.build();
    }
  }
}
