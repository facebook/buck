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
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig.SourceAbiVerificationMode;
import com.facebook.buck.jvm.java.JavaLibraryDescription.CoreArg;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@Value.Style(
  overshadowImplementation = true,
  init = "set*",
  visibility = Value.Style.ImplementationVisibility.PACKAGE
)
public abstract class DefaultJavaLibraryRules {
  public interface DefaultJavaLibraryConstructor {
    DefaultJavaLibrary newInstance(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildDeps buildDeps,
        SourcePathResolver resolver,
        JarBuildStepsFactory jarBuildStepsFactory,
        Optional<SourcePath> proguardConfig,
        SortedSet<BuildRule> firstOrderPackageableDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedDeps,
        ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
        @Nullable BuildTarget abiJar,
        @Nullable BuildTarget sourceOnlyAbiJar,
        Optional<String> mavenCoords,
        ImmutableSortedSet<BuildTarget> tests,
        boolean requiredForSourceOnlyAbi);
  }

  @org.immutables.builder.Builder.Parameter
  abstract BuildTarget getInitialBuildTarget();

  @Value.Lazy
  BuildTarget getLibraryTarget() {
    BuildTarget initialBuildTarget = getInitialBuildTarget();
    return HasJavaAbi.isLibraryTarget(initialBuildTarget)
        ? initialBuildTarget
        : HasJavaAbi.getLibraryTarget(initialBuildTarget);
  }

  @org.immutables.builder.Builder.Parameter
  abstract ProjectFilesystem getProjectFilesystem();

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleParams getInitialParams();

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleResolver getBuildRuleResolver();

  @Value.Lazy
  SourcePathRuleFinder getSourcePathRuleFinder() {
    return new SourcePathRuleFinder(getBuildRuleResolver());
  }

  @Value.Lazy
  SourcePathResolver getSourcePathResolver() {
    return DefaultSourcePathResolver.from(getSourcePathRuleFinder());
  }

  @org.immutables.builder.Builder.Parameter
  abstract ConfiguredCompilerFactory getConfiguredCompilerFactory();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaBuckConfig getJavaBuckConfig();

  @Value.Default
  DefaultJavaLibraryConstructor getConstructor() {
    return DefaultJavaLibrary::new;
  }

  @Value.NaturalOrder
  abstract ImmutableSortedSet<SourcePath> getSrcs();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<SourcePath> getResources();

  @Value.Check
  void validateResources() {
    ResourceValidator.validateResources(
        getSourcePathResolver(), getProjectFilesystem(), getResources());
  }

  abstract Optional<SourcePath> getProguardConfig();

  abstract ImmutableList<String> getPostprocessClassesCommands();

  abstract Optional<Path> getResourcesRoot();

  abstract Optional<SourcePath> getManifestFile();

  abstract Optional<String> getMavenCoords();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getTests();

  @Value.Default
  RemoveClassesPatternsMatcher getClassesToRemoveFromJar() {
    return RemoveClassesPatternsMatcher.EMPTY;
  }

  @Value.Default
  boolean getSourceOnlyAbisAllowed() {
    return true;
  }

  abstract JavacOptions getJavacOptions();

  @Nullable
  abstract JavaLibraryDeps getDeps();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaLibraryDescription.CoreArg getArgs();

  public DefaultJavaLibrary buildLibrary() {
    buildAllRules();

    return (DefaultJavaLibrary) getBuildRuleResolver().getRule(getLibraryTarget());
  }

  public BuildRule buildAbi() {
    buildAllRules();

    return getBuildRuleResolver().getRule(getInitialBuildTarget());
  }

  private void buildAllRules() {
    // To guarantee that all rules in a source-ABI pipeline are working off of the same settings,
    // we want to create them all from the same instance of this builder. To ensure this, we force
    // a request for whichever rule is closest to the root of the graph (regardless of which rule
    // was actually requested) and then create all of the rules inside that request. We're
    // requesting the rootmost rule because the rules are created from leafmost to rootmost and
    // we want any requests to block until all of the rules are built.
    BuildTarget rootmostTarget = getLibraryTarget();
    if (willProduceCompareAbis()) {
      rootmostTarget = HasJavaAbi.getVerifiedSourceAbiJar(rootmostTarget);
    } else if (willProduceClassAbi()) {
      rootmostTarget = HasJavaAbi.getClassAbiJar(rootmostTarget);
    }

    BuildRuleResolver buildRuleResolver = getBuildRuleResolver();
    buildRuleResolver.computeIfAbsent(
        rootmostTarget,
        target -> {
          CalculateSourceAbi sourceOnlyAbiRule = buildSourceOnlyAbiRule();
          CalculateSourceAbi sourceAbiRule = buildSourceAbiRule();
          DefaultJavaLibrary libraryRule = buildLibraryRule(sourceAbiRule);
          CalculateClassAbi classAbiRule = buildClassAbiRule(libraryRule);
          CompareAbis compareAbisRule;
          if (sourceOnlyAbiRule != null) {
            compareAbisRule = buildCompareAbisRule(sourceOnlyAbiRule, sourceAbiRule);
          } else {
            compareAbisRule = buildCompareAbisRule(sourceAbiRule, classAbiRule);
          }

          if (HasJavaAbi.isLibraryTarget(target)) {
            return libraryRule;
          } else if (HasJavaAbi.isClassAbiTarget(target)) {
            return classAbiRule;
          } else if (HasJavaAbi.isVerifiedSourceAbiTarget(target)) {
            return compareAbisRule;
          }

          throw new AssertionError();
        });
  }

  @Nullable
  private <T extends BuildRule & CalculateAbi, U extends BuildRule & CalculateAbi>
      CompareAbis buildCompareAbisRule(@Nullable T abi1, @Nullable U abi2) {
    if (!willProduceCompareAbis()) {
      return null;
    }
    Preconditions.checkNotNull(abi1);
    Preconditions.checkNotNull(abi2);

    BuildTarget compareAbisTarget = HasJavaAbi.getVerifiedSourceAbiJar(getLibraryTarget());
    return getBuildRuleResolver()
        .addToIndex(
            new CompareAbis(
                compareAbisTarget,
                getProjectFilesystem(),
                getInitialParams()
                    .withDeclaredDeps(ImmutableSortedSet.of(abi1, abi2))
                    .withoutExtraDeps(),
                getSourcePathResolver(),
                abi1.getSourcePathToOutput(),
                abi2.getSourcePathToOutput(),
                Preconditions.checkNotNull(getJavaBuckConfig()).getSourceAbiVerificationMode()));
  }

  @Value.Lazy
  @Nullable
  BuildTarget getAbiJar() {
    if (willProduceCompareAbis()) {
      return HasJavaAbi.getVerifiedSourceAbiJar(getLibraryTarget());
    } else if (willProduceSourceAbi()) {
      return HasJavaAbi.getSourceAbiJar(getLibraryTarget());
    } else if (willProduceClassAbi()) {
      return HasJavaAbi.getClassAbiJar(getLibraryTarget());
    }

    return null;
  }

  @Value.Lazy
  @Nullable
  BuildTarget getSourceOnlyAbiJar() {
    if (willProduceSourceOnlyAbi()) {
      return HasJavaAbi.getSourceOnlyAbiJar(getLibraryTarget());
    }

    return null;
  }

  private boolean willProduceAbiJar() {
    return !getSrcs().isEmpty() || !getResources().isEmpty() || getManifestFile().isPresent();
  }

  @Value.Lazy
  AbiGenerationMode getAbiGenerationMode() {
    AbiGenerationMode result = null;

    CoreArg args = getArgs();
    if (args != null) {
      result = args.getAbiGenerationMode().orElse(null);
    }
    if (result == null) {
      result = Preconditions.checkNotNull(getJavaBuckConfig()).getAbiGenerationMode();
    }

    if (result == AbiGenerationMode.CLASS) {
      return result;
    }

    if (!shouldBuildSourceAbi()) {
      return AbiGenerationMode.CLASS;
    }

    if (result != AbiGenerationMode.SOURCE
        && (!getSourceOnlyAbisAllowed() || !pluginsSupportSourceOnlyAbis())) {
      return AbiGenerationMode.SOURCE;
    }

    if (result == AbiGenerationMode.MIGRATING_TO_SOURCE_ONLY
        && !getConfiguredCompilerFactory().shouldMigrateToSourceOnlyAbi()) {
      return AbiGenerationMode.SOURCE;
    }

    if (result == AbiGenerationMode.SOURCE_ONLY
        && !getConfiguredCompilerFactory().shouldGenerateSourceOnlyAbi()) {
      return AbiGenerationMode.SOURCE;
    }

    return result;
  }

  @Value.Lazy
  SourceAbiVerificationMode getSourceAbiVerificationMode() {
    JavaBuckConfig javaBuckConfig = getJavaBuckConfig();
    CoreArg args = getArgs();
    SourceAbiVerificationMode result = null;

    if (args != null) {
      result = args.getSourceAbiVerificationMode().orElse(null);
    }
    if (result == null) {
      result =
          javaBuckConfig != null
              ? javaBuckConfig.getSourceAbiVerificationMode()
              : SourceAbiVerificationMode.OFF;
    }

    return result;
  }

  private boolean willProduceSourceAbi() {
    return willProduceAbiJar() && getAbiGenerationMode().isSourceAbi();
  }

  private boolean willProduceSourceOnlyAbi() {
    return willProduceSourceAbi() && !getAbiGenerationMode().usesDependencies();
  }

  private boolean willProduceClassAbi() {
    return willProduceAbiJar() && (!willProduceSourceAbi() || willProduceCompareAbis());
  }

  private boolean willProduceCompareAbis() {
    return willProduceSourceAbi()
        && getSourceAbiVerificationMode() != JavaBuckConfig.SourceAbiVerificationMode.OFF;
  }

  private boolean shouldBuildSourceAbi() {
    return getConfiguredCompilerFactory().shouldGenerateSourceAbi()
        && !getSrcs().isEmpty()
        && getPostprocessClassesCommands().isEmpty();
  }

  private boolean pluginsSupportSourceOnlyAbis() {
    ImmutableList<ResolvedJavacPluginProperties> annotationProcessors =
        Preconditions.checkNotNull(getJavacOptions())
            .getAnnotationProcessingParams()
            .getAnnotationProcessors(getProjectFilesystem(), getSourcePathResolver());

    for (ResolvedJavacPluginProperties annotationProcessor : annotationProcessors) {
      if (!annotationProcessor.getDoesNotAffectAbi()
          && !annotationProcessor.getSupportAbiGenerationFromSource()) {
        // Processor is ABI-affecting but cannot run during ABI generation from source; disallow
        return false;
      }
    }

    return true;
  }

  private DefaultJavaLibrary buildLibraryRule(@Nullable CalculateSourceAbi sourceAbiRule) {
    BuildDeps buildDeps = getFinalBuildDeps();

    if (sourceAbiRule != null) {
      buildDeps = new BuildDeps(buildDeps, ImmutableSortedSet.of(sourceAbiRule));
    }

    DefaultJavaLibrary libraryRule =
        getConstructor()
            .newInstance(
                getLibraryTarget(),
                getProjectFilesystem(),
                buildDeps,
                getSourcePathResolver(),
                getJarBuildStepsFactory(),
                getProguardConfig(),
                getClasspaths().getFirstOrderPackageableDeps(),
                Preconditions.checkNotNull(getDeps()).getExportedDeps(),
                Preconditions.checkNotNull(getDeps()).getProvidedDeps(),
                getAbiJar(),
                getSourceOnlyAbiJar(),
                getMavenCoords(),
                getTests(),
                getRequiredForSourceOnlyAbi());

    if (sourceAbiRule != null) {
      libraryRule.setSourceAbi(sourceAbiRule);
    }

    getBuildRuleResolver().addToIndex(libraryRule);
    return libraryRule;
  }

  private boolean getRequiredForSourceOnlyAbi() {
    return getArgs() != null && getArgs().getRequiredForSourceOnlyAbi();
  }

  @Value.Lazy
  Supplier<SourceOnlyAbiRuleInfo> getSourceOnlyAbiRuleInfoSupplier() {
    return () ->
        new DefaultSourceOnlyAbiRuleInfo(
            getSourcePathRuleFinder(),
            getLibraryTarget(),
            getRequiredForSourceOnlyAbi(),
            getClasspaths(),
            getClasspathsForSourceOnlyAbi());
  }

  @Nullable
  private CalculateSourceAbi buildSourceOnlyAbiRule() {
    if (!willProduceSourceOnlyAbi()) {
      return null;
    }

    BuildDeps buildDeps = getFinalBuildDepsForSourceOnlyAbi();
    JarBuildStepsFactory jarBuildStepsFactory = getJarBuildStepsFactoryForSourceOnlyAbi();

    BuildTarget sourceAbiTarget = HasJavaAbi.getSourceOnlyAbiJar(getLibraryTarget());
    return getBuildRuleResolver()
        .addToIndex(
            new CalculateSourceAbi(
                sourceAbiTarget,
                getProjectFilesystem(),
                buildDeps,
                getSourcePathRuleFinder(),
                jarBuildStepsFactory));
  }

  @Nullable
  private CalculateSourceAbi buildSourceAbiRule() {
    if (!willProduceSourceAbi()) {
      return null;
    }

    BuildDeps buildDeps = getFinalBuildDeps();
    JarBuildStepsFactory jarBuildStepsFactory = getJarBuildStepsFactory();

    BuildTarget sourceAbiTarget = HasJavaAbi.getSourceAbiJar(getLibraryTarget());
    return getBuildRuleResolver()
        .addToIndex(
            new CalculateSourceAbi(
                sourceAbiTarget,
                getProjectFilesystem(),
                buildDeps,
                getSourcePathRuleFinder(),
                jarBuildStepsFactory));
  }

  @Nullable
  private CalculateClassAbi buildClassAbiRule(DefaultJavaLibrary libraryRule) {
    if (!willProduceClassAbi()) {
      return null;
    }

    BuildTarget classAbiTarget = HasJavaAbi.getClassAbiJar(getLibraryTarget());
    return getBuildRuleResolver()
        .addToIndex(
            CalculateClassAbi.of(
                classAbiTarget,
                getSourcePathRuleFinder(),
                getProjectFilesystem(),
                getInitialParams(),
                libraryRule.getSourcePathToOutput(),
                getAbiCompatibilityMode()));
  }

  @Value.Lazy
  AbiGenerationMode getAbiCompatibilityMode() {
    return getJavaBuckConfig() == null
            || getJavaBuckConfig().getSourceAbiVerificationMode() == SourceAbiVerificationMode.OFF
        ? AbiGenerationMode.CLASS
        // Use the BuckConfig version (rather than the inferred one) because if any
        // targets are using source_only it can affect the output of other targets
        // in ways that are hard to simulate
        : getJavaBuckConfig().getAbiGenerationMode();
  }

  @Value.Lazy
  DefaultJavaLibraryClasspaths getClasspaths() {
    return DefaultJavaLibraryClasspaths.builder(getBuildRuleResolver())
        .setBuildRuleParams(getInitialParams())
        .setConfiguredCompiler(getConfiguredCompiler())
        .setDeps(Preconditions.checkNotNull(getDeps()))
        .setCompileAgainstLibraryType(getCompileAgainstLibraryType())
        .build();
  }

  @Value.Lazy
  DefaultJavaLibraryClasspaths getClasspathsForSourceOnlyAbi() {
    return DefaultJavaLibraryClasspaths.builder()
        .from(getClasspaths())
        .setShouldCreateSourceOnlyAbi(true)
        .build();
  }

  @Value.Lazy
  ConfiguredCompiler getConfiguredCompiler() {
    return getConfiguredCompilerFactory()
        .configure(
            getSourcePathResolver(),
            getSourcePathRuleFinder(),
            getProjectFilesystem(),
            getArgs(),
            getJavacOptions(),
            getBuildRuleResolver());
  }

  @Value.Lazy
  ConfiguredCompiler getConfiguredCompilerForSourceOnlyAbi() {
    return getConfiguredCompilerFactory()
        .configure(
            getSourcePathResolver(),
            getSourcePathRuleFinder(),
            getProjectFilesystem(),
            getArgs(),
            getJavacOptionsForSourceOnlyAbi(),
            getBuildRuleResolver());
  }

  @Value.Lazy
  JavacOptions getJavacOptionsForSourceOnlyAbi() {
    JavacOptions javacOptions = getJavacOptions();
    return javacOptions.withAnnotationProcessingParams(
        abiProcessorsOnly(javacOptions.getAnnotationProcessingParams()));
  }

  private AnnotationProcessingParams abiProcessorsOnly(
      AnnotationProcessingParams annotationProcessingParams) {
    Preconditions.checkArgument(annotationProcessingParams.getLegacyProcessors().isEmpty());

    return AnnotationProcessingParams.builder()
        .from(annotationProcessingParams)
        .setModernProcessors(
            annotationProcessingParams
                .getModernProcessors()
                .stream()
                .filter(processor -> !processor.getDoesNotAffectAbi())
                .collect(Collectors.toList()))
        .build();
  }

  @Value.Lazy
  BuildDeps getFinalBuildDeps() {
    return buildBuildDeps(getClasspaths());
  }

  @Value.Lazy
  BuildDeps getFinalBuildDepsForSourceOnlyAbi() {
    return buildBuildDeps(getClasspathsForSourceOnlyAbi());
  }

  private BuildDeps buildBuildDeps(DefaultJavaLibraryClasspaths classpaths) {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    depsBuilder
        // We always need the non-classpath deps, whether directly specified or specified via
        // query
        .addAll(classpaths.getNonClasspathDeps())
        // It's up to the compiler to use an ABI jar for these deps if appropriate, so we can
        // add them unconditionally
        .addAll(getConfiguredCompiler().getBuildDeps(getSourcePathRuleFinder()))
        // We always need the ABI deps (at least for rulekey computation)
        // TODO(jkeljo): It's actually incorrect to use ABIs for rulekey computation for languages
        // that can't compile against them. Generally the reason they can't compile against ABIs
        // is that the ABI generation for that language isn't fully correct.
        .addAll(classpaths.getCompileTimeClasspathAbiDeps());

    if (getCompileAgainstLibraryType() == CompileAgainstLibraryType.FULL) {
      depsBuilder.addAll(classpaths.getCompileTimeClasspathFullDeps());
    }

    return new BuildDeps(depsBuilder.build());
  }

  @Value.Lazy
  CompileAgainstLibraryType getCompileAgainstLibraryType() {
    CoreArg args = getArgs();
    CompileAgainstLibraryType result = CompileAgainstLibraryType.SOURCE_ONLY_ABI;
    if (args != null) {
      result = args.getCompileAgainst().orElse(result);
    }

    if (!getConfiguredCompilerFactory().shouldCompileAgainstAbis()) {
      result = CompileAgainstLibraryType.FULL;
    }

    return result;
  }

  @Value.Lazy
  JarBuildStepsFactory getJarBuildStepsFactory() {
    DefaultJavaLibraryClasspaths classpaths = getClasspaths();
    return new JarBuildStepsFactory(
        getProjectFilesystem(),
        getSourcePathRuleFinder(),
        getLibraryTarget(),
        getConfiguredCompiler(),
        getSrcs(),
        getResources(),
        getResourcesRoot(),
        getManifestFile(),
        getPostprocessClassesCommands(),
        classpaths.getAbiClasspath(),
        getConfiguredCompilerFactory().trackClassUsage(getJavacOptions()),
        classpaths.getCompileTimeClasspathSourcePaths(),
        getClassesToRemoveFromJar(),
        getAbiGenerationMode(),
        getAbiCompatibilityMode(),
        getSourceOnlyAbiRuleInfoSupplier());
  }

  @Value.Lazy
  JarBuildStepsFactory getJarBuildStepsFactoryForSourceOnlyAbi() {
    DefaultJavaLibraryClasspaths classpaths = getClasspathsForSourceOnlyAbi();
    return new JarBuildStepsFactory(
        getProjectFilesystem(),
        getSourcePathRuleFinder(),
        getLibraryTarget(),
        getConfiguredCompilerForSourceOnlyAbi(),
        getSrcs(),
        getResources(),
        getResourcesRoot(),
        getManifestFile(),
        getPostprocessClassesCommands(),
        classpaths.getAbiClasspath(),
        getConfiguredCompilerFactory().trackClassUsage(getJavacOptions()),
        classpaths.getCompileTimeClasspathSourcePaths(),
        getClassesToRemoveFromJar(),
        getAbiGenerationMode(),
        getAbiCompatibilityMode(),
        getSourceOnlyAbiRuleInfoSupplier());
  }

  @org.immutables.builder.Builder.AccessibleFields
  public static class Builder extends ImmutableDefaultJavaLibraryRules.Builder {
    public Builder(
        BuildTarget initialBuildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams initialParams,
        BuildRuleResolver buildRuleResolver,
        ConfiguredCompilerFactory configuredCompilerFactory,
        @Nullable JavaBuckConfig javaBuckConfig,
        @Nullable JavaLibraryDescription.CoreArg args) {
      super(
          initialBuildTarget,
          projectFilesystem,
          initialParams,
          buildRuleResolver,
          configuredCompilerFactory,
          javaBuckConfig,
          args);

      this.buildRuleResolver = buildRuleResolver;

      if (args != null) {
        setSrcs(args.getSrcs())
            .setResources(args.getResources())
            .setResourcesRoot(args.getResourcesRoot())
            .setProguardConfig(args.getProguardConfig())
            .setPostprocessClassesCommands(args.getPostprocessClassesCommands())
            .setDeps(JavaLibraryDeps.newInstance(args, buildRuleResolver))
            .setTests(args.getTests())
            .setManifestFile(args.getManifestFile())
            .setMavenCoords(args.getMavenCoords())
            .setClassesToRemoveFromJar(new RemoveClassesPatternsMatcher(args.getRemoveClasses()));
      }
    }

    Builder() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    public JavaLibraryDeps getDeps() {
      return super.deps;
    }
  }
}
