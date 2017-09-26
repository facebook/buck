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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

public final class DefaultJavaLibraryBuilder {
  public interface DefaultJavaLibraryConstructor {
    DefaultJavaLibrary newInstance(
        BuildTarget buildTarget,
        final ProjectFilesystem projectFilesystem,
        ImmutableSortedSet<BuildRule> buildDeps,
        SourcePathResolver resolver,
        JarBuildStepsFactory jarBuildStepsFactory,
        Optional<SourcePath> proguardConfig,
        SortedSet<BuildRule> fullJarDeclaredDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedDeps,
        ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
        @Nullable BuildTarget abiJar,
        Optional<String> mavenCoords,
        ImmutableSortedSet<BuildTarget> tests,
        boolean requiredForSourceAbi);
  }

  private final BuildTarget libraryTarget;
  private final BuildTarget initialBuildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final BuildRuleParams initialParams;
  @Nullable private final JavaBuckConfig javaBuckConfig;
  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ConfiguredCompilerFactory configuredCompilerFactory;
  private DefaultJavaLibraryConstructor constructor = DefaultJavaLibrary::new;
  private ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  private ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();
  private Optional<SourcePath> proguardConfig = Optional.empty();
  private ImmutableList<String> postprocessClassesCommands = ImmutableList.of();
  private Optional<Path> resourcesRoot = Optional.empty();
  private Optional<SourcePath> manifestFile = Optional.empty();
  private Optional<String> mavenCoords = Optional.empty();
  private ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  private RemoveClassesPatternsMatcher classesToRemoveFromJar = RemoveClassesPatternsMatcher.EMPTY;
  private boolean sourceAbisAllowed = true;
  @Nullable private JavacOptions initialJavacOptions = null;
  @Nullable private JavaLibraryDeps deps = null;
  @Nullable private JavaLibraryDescription.CoreArg args = null;
  @Nullable private DefaultJavaLibrary libraryRule;
  @Nullable private CalculateAbiFromSource sourceAbiRule;
  @Nullable private ImmutableSortedSet<BuildRule> finalBuildDeps;
  @Nullable private ImmutableSortedSet<BuildRule> finalFullJarDeclaredDeps;
  @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathUnfilteredFullDeps;
  @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathFullDeps;
  @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathAbiDeps;
  @Nullable private ZipArchiveDependencySupplier abiClasspath;
  @Nullable private JarBuildStepsFactory jarBuildStepsFactory;
  @Nullable private BuildTarget abiJar;
  @Nullable private JavacOptions javacOptions;
  @Nullable private ConfiguredCompiler configuredCompiler;

  public DefaultJavaLibraryBuilder(
      BuildTarget initialBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams initialParams,
      BuildRuleResolver buildRuleResolver,
      ConfiguredCompilerFactory configuredCompilerFactory,
      @Nullable JavaBuckConfig javaBuckConfig) {
    libraryTarget =
        HasJavaAbi.isLibraryTarget(initialBuildTarget)
            ? initialBuildTarget
            : HasJavaAbi.getLibraryTarget(initialBuildTarget);

    this.initialBuildTarget = initialBuildTarget;
    this.projectFilesystem = projectFilesystem;
    this.initialParams = initialParams;
    this.buildRuleResolver = buildRuleResolver;
    this.configuredCompilerFactory = configuredCompilerFactory;
    this.javaBuckConfig = javaBuckConfig;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
  }

  public DefaultJavaLibraryBuilder(
      BuildTarget initialBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams initialParams,
      BuildRuleResolver buildRuleResolver,
      ConfiguredCompilerFactory configuredCompilerFactory) {
    this(
        initialBuildTarget,
        projectFilesystem,
        initialParams,
        buildRuleResolver,
        configuredCompilerFactory,
        null);
  }

  public DefaultJavaLibraryBuilder setConstructor(DefaultJavaLibraryConstructor constructor) {
    this.constructor = constructor;
    return this;
  }

  public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.CoreArg args) {
    this.args = args;

    return setSrcs(args.getSrcs())
        .setResources(args.getResources())
        .setResourcesRoot(args.getResourcesRoot())
        .setProguardConfig(args.getProguardConfig())
        .setPostprocessClassesCommands(args.getPostprocessClassesCommands())
        .setDeps(JavaLibraryDeps.newInstance(args, buildRuleResolver))
        .setTests(args.getTests())
        .setManifestFile(args.getManifestFile())
        .setMavenCoords(args.getMavenCoords())
        .setSourceAbisAllowed(args.getGenerateAbiFromSource().orElse(sourceAbisAllowed))
        .setClassesToRemoveFromJar(new RemoveClassesPatternsMatcher(args.getRemoveClasses()));
  }

  public DefaultJavaLibraryBuilder setDeps(JavaLibraryDeps deps) {
    this.deps = deps;
    return this;
  }

  public DefaultJavaLibraryBuilder setJavacOptions(JavacOptions javacOptions) {
    this.initialJavacOptions = javacOptions;
    return this;
  }

  public DefaultJavaLibraryBuilder setSourceAbisAllowed(boolean sourceAbisAllowed) {
    this.sourceAbisAllowed = sourceAbisAllowed;
    return this;
  }

  public DefaultJavaLibraryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    this.srcs = srcs;
    return this;
  }

  public DefaultJavaLibraryBuilder setResources(ImmutableSortedSet<SourcePath> resources) {
    this.resources =
        ResourceValidator.validateResources(sourcePathResolver, projectFilesystem, resources);
    return this;
  }

  public DefaultJavaLibraryBuilder setProguardConfig(Optional<SourcePath> proguardConfig) {
    this.proguardConfig = proguardConfig;
    return this;
  }

  public DefaultJavaLibraryBuilder setPostprocessClassesCommands(
      ImmutableList<String> postprocessClassesCommands) {
    this.postprocessClassesCommands = postprocessClassesCommands;
    return this;
  }

  public DefaultJavaLibraryBuilder setResourcesRoot(Optional<Path> resourcesRoot) {
    this.resourcesRoot = resourcesRoot;
    return this;
  }

  public DefaultJavaLibraryBuilder setManifestFile(Optional<SourcePath> manifestFile) {
    this.manifestFile = manifestFile;
    return this;
  }

  public DefaultJavaLibraryBuilder setMavenCoords(Optional<String> mavenCoords) {
    this.mavenCoords = mavenCoords;
    return this;
  }

  public DefaultJavaLibraryBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    this.tests = tests;
    return this;
  }

  public DefaultJavaLibraryBuilder setClassesToRemoveFromJar(
      RemoveClassesPatternsMatcher classesToRemoveFromJar) {
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    return this;
  }

  @Nullable
  public JavaLibraryDeps getDeps() {
    return deps;
  }

  public DefaultJavaLibrary buildLibrary() {
    return getLibraryRule(false);
  }

  public BuildRule buildAbi() {
    if (HasJavaAbi.isClassAbiTarget(initialBuildTarget)) {
      return buildAbiFromClasses();
    } else if (HasJavaAbi.isSourceAbiTarget(initialBuildTarget)) {
      CalculateAbiFromSource abiRule = getSourceAbiRule(false);
      getLibraryRule(true);
      return abiRule;
    } else if (HasJavaAbi.isVerifiedSourceAbiTarget(initialBuildTarget)) {
      BuildRule classAbi = buildRuleResolver.requireRule(HasJavaAbi.getClassAbiJar(libraryTarget));
      BuildRule sourceAbi =
          buildRuleResolver.requireRule(HasJavaAbi.getSourceAbiJar(libraryTarget));

      return new CompareAbis(
          initialBuildTarget,
          projectFilesystem,
          initialParams
              .withDeclaredDeps(ImmutableSortedSet.of(classAbi, sourceAbi))
              .withoutExtraDeps(),
          sourcePathResolver,
          classAbi.getSourcePathToOutput(),
          sourceAbi.getSourcePathToOutput(),
          javaBuckConfig.getSourceAbiVerificationMode());
    }

    throw new AssertionError(
        String.format(
            "%s is not an ABI target but went down the ABI codepath", initialBuildTarget));
  }

  @Nullable
  private BuildTarget getAbiJar() {
    if (!willProduceOutputJar()) {
      return null;
    }

    if (abiJar == null) {
      if (shouldBuildAbiFromSource()) {
        JavaBuckConfig.SourceAbiVerificationMode sourceAbiVerificationMode =
            javaBuckConfig.getSourceAbiVerificationMode();
        abiJar =
            sourceAbiVerificationMode == JavaBuckConfig.SourceAbiVerificationMode.OFF
                ? HasJavaAbi.getSourceAbiJar(libraryTarget)
                : HasJavaAbi.getVerifiedSourceAbiJar(libraryTarget);
      } else {
        abiJar = HasJavaAbi.getClassAbiJar(libraryTarget);
      }
    }

    return abiJar;
  }

  private boolean willProduceOutputJar() {
    return !srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent();
  }

  private boolean willProduceSourceAbi() {
    return willProduceOutputJar() && shouldBuildAbiFromSource();
  }

  private boolean shouldBuildAbiFromSource() {
    return isCompilingJava()
        && !srcs.isEmpty()
        && sourceAbisEnabled()
        && sourceAbisAllowed
        && postprocessClassesCommands.isEmpty();
  }

  private boolean isCompilingJava() {
    return getConfiguredCompiler() instanceof JavacToJarStepFactory;
  }

  private boolean sourceAbisEnabled() {
    return javaBuckConfig != null && javaBuckConfig.shouldGenerateAbisFromSource();
  }

  private DefaultJavaLibrary getLibraryRule(boolean addToIndex) {
    if (libraryRule == null) {
      ImmutableSortedSet.Builder<BuildRule> buildDepsBuilder = ImmutableSortedSet.naturalOrder();

      buildDepsBuilder.addAll(getFinalBuildDeps());
      CalculateAbiFromSource sourceAbiRule = null;
      if (willProduceSourceAbi()) {
        sourceAbiRule = getSourceAbiRule(true);
        buildDepsBuilder.add(sourceAbiRule);
      }

      libraryRule =
          constructor.newInstance(
              initialBuildTarget,
              projectFilesystem,
              buildDepsBuilder.build(),
              sourcePathResolver,
              getJarBuildStepsFactory(),
              proguardConfig,
              getFinalFullJarDeclaredDeps(),
              Preconditions.checkNotNull(deps).getExportedDeps(),
              Preconditions.checkNotNull(deps).getProvidedDeps(),
              getAbiJar(),
              mavenCoords,
              tests,
              getRequiredForSourceAbi());

      if (sourceAbiRule != null) {
        libraryRule.setSourceAbi(sourceAbiRule);
      }

      if (addToIndex) {
        buildRuleResolver.addToIndex(libraryRule);
      }
    }

    return libraryRule;
  }

  private boolean getRequiredForSourceAbi() {
    return args != null && args.getRequiredForSourceAbi();
  }

  private CalculateAbiFromSource getSourceAbiRule(boolean addToIndex) {
    BuildTarget abiTarget = HasJavaAbi.getSourceAbiJar(libraryTarget);
    if (sourceAbiRule == null) {
      sourceAbiRule =
          new CalculateAbiFromSource(
              abiTarget,
              projectFilesystem,
              getFinalBuildDeps(),
              ruleFinder,
              getJarBuildStepsFactory());
      if (addToIndex) {
        buildRuleResolver.addToIndex(sourceAbiRule);
      }
    }
    return sourceAbiRule;
  }

  private BuildRule buildAbiFromClasses() {
    BuildTarget abiTarget = HasJavaAbi.getClassAbiJar(libraryTarget);
    BuildRule libraryRule = buildRuleResolver.requireRule(libraryTarget);

    return CalculateAbiFromClasses.of(
        abiTarget,
        ruleFinder,
        projectFilesystem,
        initialParams,
        Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()),
        javaBuckConfig != null
            && javaBuckConfig.getSourceAbiVerificationMode()
                != JavaBuckConfig.SourceAbiVerificationMode.OFF);
  }

  private ImmutableSortedSet<BuildRule> getFinalFullJarDeclaredDeps() {
    if (finalFullJarDeclaredDeps == null) {
      finalFullJarDeclaredDeps =
          ImmutableSortedSet.copyOf(
              Iterables.concat(
                  Preconditions.checkNotNull(deps).getDeps(),
                  getConfiguredCompiler().getDeclaredDeps(ruleFinder)));
    }

    return finalFullJarDeclaredDeps;
  }

  private ImmutableSortedSet<SourcePath> getFinalCompileTimeClasspathSourcePaths() {
    ImmutableSortedSet<BuildRule> buildRules =
        configuredCompilerFactory.compileAgainstAbis()
            ? getCompileTimeClasspathAbiDeps()
            : getCompileTimeClasspathFullDeps();

    return buildRules
        .stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    if (compileTimeClasspathFullDeps == null) {
      compileTimeClasspathFullDeps =
          getCompileTimeClasspathUnfilteredFullDeps()
              .stream()
              .filter(dep -> dep instanceof HasJavaAbi)
              .collect(MoreCollectors.toImmutableSortedSet());
    }

    return compileTimeClasspathFullDeps;
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
    if (compileTimeClasspathAbiDeps == null) {
      compileTimeClasspathAbiDeps =
          JavaLibraryRules.getAbiRules(buildRuleResolver, getCompileTimeClasspathFullDeps());
    }

    return compileTimeClasspathAbiDeps;
  }

  private ZipArchiveDependencySupplier getAbiClasspath() {
    if (abiClasspath == null) {
      abiClasspath =
          new ZipArchiveDependencySupplier(
              ruleFinder,
              getCompileTimeClasspathAbiDeps()
                  .stream()
                  .map(BuildRule::getSourcePathToOutput)
                  .collect(MoreCollectors.toImmutableSortedSet()));
    }

    return abiClasspath;
  }

  private ConfiguredCompiler getConfiguredCompiler() {
    if (configuredCompiler == null) {
      configuredCompiler =
          configuredCompilerFactory.configure(args, getJavacOptions(), buildRuleResolver);
    }

    return configuredCompiler;
  }

  private ImmutableSortedSet<BuildRule> getFinalBuildDeps() {
    if (finalBuildDeps == null) {
      ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();

      depsBuilder
          // We always need the non-classpath deps, whether directly specified or specified via
          // query
          .addAll(Sets.difference(initialParams.getBuildDeps(), getCompileTimeClasspathFullDeps()))
          .addAll(
              Sets.difference(
                  getCompileTimeClasspathUnfilteredFullDeps(), getCompileTimeClasspathFullDeps()))
          // It's up to the compiler to use an ABI jar for these deps if appropriate, so we can
          // add them unconditionally
          .addAll(getConfiguredCompiler().getBuildDeps(ruleFinder))
          // We always need the ABI deps (at least for rulekey computation)
          // TODO(jkeljo): It's actually incorrect to use ABIs for rulekey computation for languages
          // that can't compile against them. Generally the reason they can't compile against ABIs
          // is that the ABI generation for that language isn't fully correct.
          .addAll(getCompileTimeClasspathAbiDeps());

      if (!configuredCompilerFactory.compileAgainstAbis()) {
        depsBuilder.addAll(getCompileTimeClasspathFullDeps());
      }

      finalBuildDeps = depsBuilder.build();
    }

    return finalBuildDeps;
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    if (compileTimeClasspathUnfilteredFullDeps == null) {
      Iterable<BuildRule> firstOrderDeps =
          Iterables.concat(getFinalFullJarDeclaredDeps(), deps.getProvidedDeps());

      ImmutableSortedSet<BuildRule> rulesExportedByDependencies =
          BuildRules.getExportedRules(firstOrderDeps);

      compileTimeClasspathUnfilteredFullDeps =
          RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
              .collect(MoreCollectors.toImmutableSortedSet());
    }

    return compileTimeClasspathUnfilteredFullDeps;
  }

  private JarBuildStepsFactory getJarBuildStepsFactory() {
    if (jarBuildStepsFactory == null) {
      jarBuildStepsFactory =
          new JarBuildStepsFactory(
              projectFilesystem,
              ruleFinder,
              getConfiguredCompiler(),
              srcs,
              resources,
              resourcesRoot,
              manifestFile,
              postprocessClassesCommands,
              getAbiClasspath(),
              configuredCompilerFactory.trackClassUsage(getJavacOptions()),
              getFinalCompileTimeClasspathSourcePaths(),
              classesToRemoveFromJar,
              getRequiredForSourceAbi());
    }
    return jarBuildStepsFactory;
  }

  private JavacOptions getJavacOptions() {
    if (javacOptions == null) {
      javacOptions = Preconditions.checkNotNull(initialJavacOptions);
    }
    return javacOptions;
  }
}
