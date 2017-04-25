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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.PathShortener;
import com.facebook.buck.cxx.Preprocessor;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.OptionalCompat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HaskellCompileRule extends AbstractBuildRule {

  @AddToRuleKey private final Tool compiler;

  private final HaskellVersion haskellVersion;

  @AddToRuleKey private final ImmutableList<String> flags;

  private final PreprocessorFlags ppFlags;
  private final CxxPlatform cxxPlatform;

  @AddToRuleKey private final CxxSourceRuleFactory.PicType picType;

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
      BuildRuleParams buildRuleParams,
      Tool compiler,
      HaskellVersion haskellVersion,
      ImmutableList<String> flags,
      PreprocessorFlags ppFlags,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<SourcePath> includes,
      ImmutableSortedMap<String, HaskellPackage> exposedPackages,
      ImmutableSortedMap<String, HaskellPackage> packages,
      HaskellSources sources,
      Preprocessor preprocessor) {
    super(buildRuleParams);
    this.compiler = compiler;
    this.haskellVersion = haskellVersion;
    this.flags = flags;
    this.ppFlags = ppFlags;
    this.cxxPlatform = cxxPlatform;
    this.picType = picType;
    this.main = main;
    this.packageInfo = packageInfo;
    this.includes = includes;
    this.exposedPackages = exposedPackages;
    this.packages = packages;
    this.sources = sources;
    this.preprocessor = preprocessor;
  }

  public static HaskellCompileRule from(
      BuildTarget target,
      BuildRuleParams baseParams,
      SourcePathRuleFinder ruleFinder,
      final Tool compiler,
      HaskellVersion haskellVersion,
      ImmutableList<String> flags,
      final PreprocessorFlags ppFlags,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      final ImmutableList<SourcePath> includes,
      final ImmutableSortedMap<String, HaskellPackage> exposedPackages,
      final ImmutableSortedMap<String, HaskellPackage> packages,
      final HaskellSources sources,
      Preprocessor preprocessor) {
    Supplier<ImmutableSortedSet<BuildRule>> declaredDeps =
        Suppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(compiler.getDeps(ruleFinder))
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
        baseParams
            .withBuildTarget(target)
            .copyReplacingDeclaredAndExtraDeps(
                declaredDeps, Suppliers.ofInstance(ImmutableSortedSet.of())),
        compiler,
        haskellVersion,
        flags,
        ppFlags,
        cxxPlatform,
        picType,
        main,
        packageInfo,
        includes,
        exposedPackages,
        packages,
        sources,
        preprocessor);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    ppFlags.appendToRuleKey(sink, cxxPlatform.getCompilerDebugPathSanitizer());
    sink.setReflectively("headers", ppFlags.getIncludes());
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
    return MoreIterables.zipAndConcat(Iterables.cycle("-optP"), cxxToolFlags.getAllFlags());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    buildableContext.recordArtifact(getObjectDir());
    buildableContext.recordArtifact(getInterfaceDir());
    buildableContext.recordArtifact(getStubDir());
    return new ImmutableList.Builder<Step>()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getObjectDir()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getInterfaceDir()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getStubDir()))
        .add(
            new ShellStep(getProjectFilesystem().getRootPath()) {

              @Override
              public ImmutableMap<String, String> getEnvironmentVariables(
                  ExecutionContext context) {
                return ImmutableMap.<String, String>builder()
                    .putAll(super.getEnvironmentVariables(context))
                    .putAll(compiler.getEnvironment(buildContext.getSourcePathResolver()))
                    .build();
              }

              @Override
              protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
                SourcePathResolver resolver = buildContext.getSourcePathResolver();
                return ImmutableList.<String>builder()
                    .addAll(compiler.getCommandPrefix(resolver))
                    .addAll(flags)
                    .add("-no-link")
                    .addAll(
                        picType == CxxSourceRuleFactory.PicType.PIC
                            ? ImmutableList.of("-dynamic", "-fPIC", "-hisuf", "dyn_hi")
                            : ImmutableList.of())
                    .addAll(
                        MoreIterables.zipAndConcat(
                            Iterables.cycle("-main-is"), OptionalCompat.asSet(main)))
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
            })
        .build();
  }

  @Override
  public boolean isCacheable() {
    return haskellVersion.getMajorVersion() >= 8;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getInterfaceDir());
  }

  public ImmutableList<SourcePath> getObjects() {
    ImmutableList.Builder<SourcePath> objects = ImmutableList.builder();
    for (String module : sources.getModuleNames()) {
      objects.add(
          new ExplicitBuildTargetSourcePath(
              getBuildTarget(),
              getObjectDir().resolve(module.replace('.', File.separatorChar) + ".o")));
    }
    return objects.build();
  }

  public ImmutableSortedSet<String> getModules() {
    return sources.getModuleNames();
  }

  public SourcePath getInterfaces() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getInterfaceDir());
  }

  @VisibleForTesting
  protected ImmutableList<String> getFlags() {
    return flags;
  }
}
