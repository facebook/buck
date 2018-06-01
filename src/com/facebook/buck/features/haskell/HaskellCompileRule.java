/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PathShortener;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HaskellCompileRule extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private static final Logger LOG = Logger.get(HaskellCompileRule.class);

  @AddToRuleKey private final Tool compiler;

  private final HaskellVersion haskellVersion;

  @AddToRuleKey private final ImmutableList<String> flags;

  @AddToRuleKey private final PreprocessorFlags ppFlags;
  private final CxxPlatform cxxPlatform;

  @AddToRuleKey private boolean pic;

  @AddToRuleKey private final boolean hsProfile;

  @AddToRuleKey private final Optional<String> main;

  /**
   * Optional package info. If specified, the package name and version are baked into the
   * compilation.
   */
  @AddToRuleKey private final Optional<HaskellPackageInfo> packageInfo;

  @AddToRuleKey private final ImmutableList<SourcePath> includes;

  /** Packages providing modules that modules from this compilation can directly import. */
  @AddToRuleKey private final ImmutableSortedMap<String, HaskellPackage> exposedPackages;

  /**
   * Packages that are transitively used by the exposed packages. Modules in this compilation cannot
   * import modules from these.
   */
  @AddToRuleKey private final ImmutableSortedMap<String, HaskellPackage> packages;

  @AddToRuleKey private final HaskellSources sources;

  @AddToRuleKey private final Preprocessor preprocessor;

  private HaskellCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool compiler,
      HaskellVersion haskellVersion,
      ImmutableList<String> flags,
      PreprocessorFlags ppFlags,
      CxxPlatform cxxPlatform,
      PicType picType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<SourcePath> includes,
      ImmutableSortedMap<String, HaskellPackage> exposedPackages,
      ImmutableSortedMap<String, HaskellPackage> packages,
      HaskellSources sources,
      Preprocessor preprocessor) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.compiler = compiler;
    this.haskellVersion = haskellVersion;
    this.flags = flags;
    this.ppFlags = ppFlags;
    this.cxxPlatform = cxxPlatform;
    this.pic = (picType == PicType.PIC);
    this.hsProfile = hsProfile;
    this.main = main;
    this.packageInfo = packageInfo;
    this.includes = includes;
    this.exposedPackages = exposedPackages;
    this.packages = packages;
    this.sources = sources;
    this.preprocessor = preprocessor;

    Preconditions.checkState(!(pic && hsProfile), "Currently don't support profiled PIC.");
  }

  public static HaskellCompileRule from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      Tool compiler,
      HaskellVersion haskellVersion,
      ImmutableList<String> flags,
      PreprocessorFlags ppFlags,
      CxxPlatform cxxPlatform,
      PicType picType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<SourcePath> includes,
      ImmutableSortedMap<String, HaskellPackage> exposedPackages,
      ImmutableSortedMap<String, HaskellPackage> packages,
      HaskellSources sources,
      Preprocessor preprocessor) {
    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(compiler, ruleFinder))
                    .addAll(ppFlags.getDeps(ruleFinder))
                    .addAll(ruleFinder.filterBuildRuleInputs(includes))
                    .addAll(sources.getDeps(ruleFinder))
                    .addAll(
                        Stream.of(exposedPackages, packages)
                            .flatMap(packageMap -> packageMap.values().stream())
                            .flatMap(pkg -> pkg.getDeps(ruleFinder))
                            .iterator())
                    .build());
    return new HaskellCompileRule(
        target,
        projectFilesystem,
        baseParams.withDeclaredDeps(declaredDeps).withoutExtraDeps(),
        compiler,
        haskellVersion,
        flags,
        ppFlags,
        cxxPlatform,
        picType,
        hsProfile,
        main,
        packageInfo,
        includes,
        exposedPackages,
        packages,
        sources,
        preprocessor);
  }

  private Path getObjectDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("objects");
  }

  private Path getInterfaceDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("interfaces");
  }

  /** @return the path where the compiler places generated FFI stub files. */
  private Path getStubDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s").resolve("stubs");
  }

  private Iterable<String> getPackageNameArgs() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    if (packageInfo.isPresent()) {
      if (haskellVersion.getMajorVersion() >= 8) {
        builder.add("-package-name", packageInfo.get().getName());
      } else {
        builder.add(
            "-package-name", packageInfo.get().getName() + '-' + packageInfo.get().getVersion());
      }
    }
    return builder.build();
  }

  /** @return the arguments to pass to the compiler to build against package dependencies. */
  private Iterable<String> getPackageArgs(SourcePathResolver resolver) {
    Set<String> packageDbs = new TreeSet<>();
    Set<String> hidden = new TreeSet<>();
    Set<String> exposed = new TreeSet<>();

    for (HaskellPackage haskellPackage : packages.values()) {
      packageDbs.add(resolver.getAbsolutePath(haskellPackage.getPackageDb()).toString());
      hidden.add(
          String.format(
              "%s-%s", haskellPackage.getInfo().getName(), haskellPackage.getInfo().getVersion()));
    }

    for (HaskellPackage haskellPackage : exposedPackages.values()) {
      packageDbs.add(resolver.getAbsolutePath(haskellPackage.getPackageDb()).toString());
      exposed.add(
          String.format(
              "%s-%s", haskellPackage.getInfo().getName(), haskellPackage.getInfo().getVersion()));
    }

    // We add all package DBs, and explicit expose or hide packages depending on whether they are
    // exposed or not.  This allows us to support setups that either add `-hide-all-packages` or
    // not.
    return ImmutableList.<String>builder()
        .addAll(MoreIterables.zipAndConcat(Iterables.cycle("-package-db"), packageDbs))
        .addAll(MoreIterables.zipAndConcat(Iterables.cycle("-package"), exposed))
        .addAll(MoreIterables.zipAndConcat(Iterables.cycle("-hide-package"), hidden))
        .build();
  }

  private Iterable<String> getPreprocessorFlags(SourcePathResolver resolver) {
    CxxToolFlags cxxToolFlags =
        ppFlags.toToolFlags(
            resolver,
            PathShortener.identity(),
            CxxDescriptionEnhancer.frameworkPathToSearchPath(cxxPlatform, resolver),
            preprocessor,
            /* pch */ Optional.empty());
    return MoreIterables.zipAndConcat(
        Iterables.cycle("-optP"), Arg.stringify(cxxToolFlags.getAllFlags(), resolver));
  }

  private class GhcStep extends ShellStep {

    private BuildContext buildContext;

    public GhcStep(BuildTarget buildTarget, Path rootPath, BuildContext buildContext) {
      super(Optional.of(buildTarget), rootPath);
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
      ImmutableList<String> extraArgs = null;
      if (pic) {
        extraArgs = HaskellDescriptionUtils.PIC_FLAGS;
      } else if (hsProfile) {
        extraArgs = HaskellDescriptionUtils.PROF_FLAGS;
      } else {
        extraArgs = ImmutableList.of();
      }

      return getCommandWithExtraArgs(extraArgs);
    }

    private ImmutableList<String> getCommandWithExtraArgs(ImmutableList<String> extraArgs) {
      SourcePathResolver resolver = buildContext.getSourcePathResolver();

      return ImmutableList.<String>builder()
          .addAll(compiler.getCommandPrefix(resolver))
          .addAll(flags)
          .add("-no-link")
          .addAll(extraArgs)
          .addAll(
              MoreIterables.zipAndConcat(
                  Iterables.cycle("-main-is"), Optionals.toStream(main).toOnceIterable()))
          .addAll(getPackageNameArgs())
          .addAll(getPreprocessorFlags(buildContext.getSourcePathResolver()))
          .add("-odir", getProjectFilesystem().resolve(getObjectDir()).toString())
          .add("-hidir", getProjectFilesystem().resolve(getInterfaceDir()).toString())
          .add("-stubdir", getProjectFilesystem().resolve(getStubDir()).toString())
          .add(
              "-i"
                  + includes
                      .stream()
                      .map(resolver::getAbsolutePath)
                      .map(Object::toString)
                      .collect(Collectors.joining(":")))
          .addAll(getPackageArgs(buildContext.getSourcePathResolver()))
          .addAll(
              sources
                  .getSourcePaths()
                  .stream()
                  .map(resolver::getAbsolutePath)
                  .map(Object::toString)
                  .iterator())
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
        .add(new GhcStep(getBuildTarget(), getProjectFilesystem().getRootPath(), buildContext));

    return steps.build();
  }

  @Override
  public boolean isCacheable() {
    return haskellVersion.getMajorVersion() >= 8;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getInterfaceDir());
  }

  private String getObjectSuffix() {
    if (hsProfile) {
      return "p_o";
    } else {
      return "o";
    }
  }

  private String getInterfaceSuffix() {
    if (pic) {
      return "dyn_hi";
    } else if (hsProfile) {
      return "p_hi";
    } else {
      return "hi";
    }
  }

  public ImmutableList<SourcePath> getObjects() {
    String suffix = "." + getObjectSuffix();

    ImmutableList.Builder<SourcePath> objects = ImmutableList.builder();
    for (String module : sources.getModuleNames()) {
      objects.add(
          ExplicitBuildTargetSourcePath.of(
              getBuildTarget(),
              getObjectDir().resolve(module.replace('.', File.separatorChar) + suffix)));
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
    return flags;
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
                      RichStream.from(sources.getModuleNames())
                          .map(s -> root.resolve(s.replace('.', File.separatorChar) + "." + suffix))
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
