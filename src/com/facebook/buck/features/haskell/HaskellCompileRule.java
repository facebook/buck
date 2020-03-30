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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class HaskellCompileRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private static final Logger LOG = Logger.get(HaskellCompileRule.class);

  @AddToRuleKey private final Tool compiler;

  @AddToRuleKey private final HaskellCompilerFlags flags;

  private final HaskellPlatform platform;

  @AddToRuleKey private final Linker.LinkableDepType depType;

  @AddToRuleKey private final boolean hsProfile;

  @AddToRuleKey private final Optional<String> main;

  @AddToRuleKey private final Optional<HaskellPackageInfo> packageInfo;

  @AddToRuleKey private final HaskellSources sources;

  @AddToRuleKey private final Preprocessor preprocessor;

  private HaskellCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool compiler,
      HaskellCompilerFlags flags,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      HaskellSources sources,
      Preprocessor preprocessor) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.compiler = compiler;
    this.flags = flags;
    this.platform = platform;
    this.depType = depType;
    this.hsProfile = hsProfile;
    this.main = main;
    this.packageInfo = packageInfo;
    this.sources = sources;
    this.preprocessor = preprocessor;
  }

  public static HaskellCompileRule from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      Tool compiler,
      HaskellCompilerFlags flags,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      HaskellSources sources,
      Preprocessor preprocessor) {
    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(compiler, ruleFinder))
                    .addAll(flags.getDeps(ruleFinder))
                    .addAll(sources.getDeps(ruleFinder))
                    .build());
    return new HaskellCompileRule(
        target,
        projectFilesystem,
        baseParams.withDeclaredDeps(declaredDeps).withoutExtraDeps(),
        compiler,
        flags,
        platform,
        depType,
        hsProfile,
        main,
        packageInfo,
        sources,
        preprocessor);
  }

  private Path getObjectDir() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("objects");
  }

  private Path getInterfaceDir() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("interfaces");
  }

  /** @return the path where the compiler places generated FFI stub files. */
  private Path getStubDir() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("stubs");
  }

  private Path getScratchDir() {
    return BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private Path getArgsfile() {
    return getProjectFilesystem().resolve(getScratchDir()).resolve("ghc.argsfile");
  }

  private Iterable<String> getPackageNameArgs() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    if (packageInfo.isPresent()) {
      if (platform.getHaskellVersion().getMajorVersion() >= 8) {
        builder.add("-package-name", packageInfo.get().getName());
      } else {
        builder.add(
            "-package-name", packageInfo.get().getName() + '-' + packageInfo.get().getVersion());
      }
    }
    return builder.build();
  }

  private Iterable<String> getPreprocessorFlags(SourcePathResolverAdapter resolver) {
    CxxToolFlags cxxToolFlags =
        flags
            .getPreprocessorFlags()
            .toToolFlags(
                resolver,
                PathShortener.identity(),
                CxxDescriptionEnhancer.frameworkPathToSearchPath(
                    platform.getCxxPlatform(), resolver),
                preprocessor,
                /* pch */ Optional.empty());
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-optP"), Arg.stringify(cxxToolFlags.getAllFlags(), resolver));
  }

  private Iterable<String> getSourceArguments(SourcePathResolverAdapter resolver) {
    return sources.getSourcePaths().stream()
        .map(resolver::getAbsolutePath)
        .map(Object::toString)
        .collect(Collectors.toList());
  }

  private Iterable<String> getCompilerArguments(SourcePathResolverAdapter resolver) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    builder.addAll(flags.getAdditionalFlags()).add("-no-link");

    if (depType == Linker.LinkableDepType.SHARED) {
      builder.addAll(HaskellDescriptionUtils.DYNAMIC_FLAGS);
    } else if (hsProfile) {
      builder.addAll(HaskellDescriptionUtils.PROF_FLAGS);
    }
    if (depType == Linker.LinkableDepType.SHARED) {
      // -dynamic implies -fexternal-dynamic-refs but -fexternal-dynamic-refs
      // is introduced in ghc-8.6, so let us not add this redundant flag
      // so that older ghc can still be supported.
      builder.addAll(
          Iterables.filter(
              HaskellDescriptionUtils.PIC_FLAGS, i -> !i.equals("-fexternal-dynamic-refs")));
    } else if (depType == Linker.LinkableDepType.STATIC_PIC) {
      builder.addAll(HaskellDescriptionUtils.PIC_FLAGS);
    }

    builder
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-main-is"), RichStream.from(main).toOnceIterable()))
        .addAll(getPackageNameArgs())
        .addAll(getPreprocessorFlags(resolver))
        .add("-odir", getProjectFilesystem().resolve(getObjectDir()).toString())
        .add("-hidir", getProjectFilesystem().resolve(getInterfaceDir()).toString())
        .add("-stubdir", getProjectFilesystem().resolve(getStubDir()).toString())
        .add("-i")
        .addAll(flags.getPackageFlags(platform, resolver));

    if (platform.shouldUseArgsfile()) {
      builder.add("@" + getArgsfile());
    } else {
      builder.addAll(getSourceArguments(resolver));
    }

    return builder.build();
  }

  private class WriteArgsfileStep implements Step {

    private BuildContext buildContext;

    public WriteArgsfileStep(BuildContext buildContext) {
      this.buildContext = buildContext;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      getProjectFilesystem().createParentDirs(getArgsfile());
      // we write the source file arguments to @ghc.argsfile as this is the
      // problematic part when we exceed the argument size limit.
      // we pass the other flags as they are directly, so that if we have a
      // wrapper script that preprocess compiler flags, it will get a chance to
      // mutate those flags while passing @ghc.argsfile as it is.
      getProjectFilesystem()
          .writeLinesToPath(
              Iterables.transform(
                  getSourceArguments(buildContext.getSourcePathResolver()),
                  Escaper.ARGFILE_ESCAPER::apply),
              getArgsfile());
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write-ghc-argsfile";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "Write argsfile for ghc";
    }
  }

  private class GhcStep extends ShellStep {

    private BuildContext buildContext;

    public GhcStep(AbsPath rootPath, BuildContext buildContext) {
      super(rootPath);
      this.buildContext = buildContext;
    }

    @Override
    public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
      return ImmutableMap.<String, String>builder()
          .putAll(super.getEnvironmentVariables(context))
          .putAll(compiler.getEnvironment(buildContext.getSourcePathResolver()))
          .build();
    }

    @Override
    protected boolean shouldPrintStderr(Verbosity verbosity) {
      return !verbosity.isSilent();
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      return ImmutableList.<String>builder()
          .addAll(compiler.getCommandPrefix(buildContext.getSourcePathResolver()))
          .addAll(getCompilerArguments(buildContext.getSourcePathResolver()))
          .build();
    }

    @Override
    public String getShortName() {
      return "haskell-compile";
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    buildableContext.recordArtifact(getObjectDir());
    buildableContext.recordArtifact(getInterfaceDir());
    buildableContext.recordArtifact(getStubDir());

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps
        .add(prepareOutputDir("object", getObjectDir(), getObjectSuffix()))
        .add(prepareOutputDir("interface", getInterfaceDir(), getInterfaceSuffix()))
        .add(prepareOutputDir("stub", getStubDir(), "h"))
        .add(new WriteArgsfileStep(buildContext))
        .add(new GhcStep(getProjectFilesystem().getRootPath(), buildContext));

    return steps.build();
  }

  @Override
  public boolean isCacheable() {
    return platform.getHaskellVersion().getMajorVersion() >= 8;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getInterfaceDir());
  }

  private String getObjectSuffix() {
    if (depType == Linker.LinkableDepType.SHARED) {
      return "dyn_o";
    } else if (hsProfile) {
      return "p_o";
    } else {
      return "o";
    }
  }

  private String getInterfaceSuffix() {
    if (depType == Linker.LinkableDepType.SHARED) {
      return "dyn_hi";
    } else if (hsProfile) {
      return "p_hi";
    } else {
      return "hi";
    }
  }

  public ImmutableList<SourcePath> getObjects() {
    ImmutableList.Builder<SourcePath> objects = ImmutableList.builder();
    for (HaskellSourceModule module : sources.getModuleMap().keySet()) {
      // We should not link the .o-boot files, they are empty stub files for
      // incremental compilation.
      if (module.getSourceType() == HaskellSourceModule.SourceType.HsSrcFile) {
        objects.add(
            ExplicitBuildTargetSourcePath.of(
                getBuildTarget(), getObjectDir().resolve(module.getOutputPath(getObjectSuffix()))));
      }
    }
    return objects.build();
  }

  public ImmutableSortedSet<String> getModules() {
    return sources.getModuleNames();
  }

  public SourcePath getInterfaces() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getInterfaceDir());
  }

  public SourcePath getObjectsDir() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getObjectDir());
  }

  public SourcePath getStubsDir() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getStubDir());
  }

  @VisibleForTesting
  protected ImmutableList<String> getFlags() {
    return flags.getAdditionalFlags();
  }

  /**
   * @return a {@link Step} which removes outputs which don't correspond to this rule's modules from
   *     the given output dir, as the module-derived outputs themselves will be controlled by the
   *     haskell compiler.
   */
  private Step prepareOutputDir(String name, Path root, String suffix) {
    return new AbstractExecutionStep(String.format("preparing %s output dir", name)) {
      @Override
      public StepExecutionResult execute(ExecutionContext context) throws IOException {
        getProjectFilesystem().mkdirs(root);
        getProjectFilesystem()
            .walkRelativeFileTree(
                root,
                new SimpleFileVisitor<Path>() {

                  // Only leave paths which would be overwritten when invoking the compiler.
                  private final Set<Path> allowedPaths =
                      RichStream.from(sources.getOutputPaths(suffix))
                          .map(path -> root.resolve(path))
                          .toImmutableSet();

                  @Override
                  public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                      throws IOException {
                    Preconditions.checkState(!file.isAbsolute());
                    if (!allowedPaths.contains(file)) {
                      LOG.verbose("cleaning " + file);
                      getProjectFilesystem().deleteFileAtPath(file);
                    }
                    return super.visitFile(file, attrs);
                  }

                  @Override
                  public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                      throws IOException {
                    Preconditions.checkState(!dir.isAbsolute());
                    if (!dir.equals(root)
                        && getProjectFilesystem().getDirectoryContents(dir).isEmpty()) {
                      LOG.verbose("cleaning " + dir);
                      getProjectFilesystem().deleteFileAtPath(dir);
                    }
                    return super.postVisitDirectory(dir, exc);
                  }
                });
        return StepExecutionResults.SUCCESS;
      }
    };
  }
}
