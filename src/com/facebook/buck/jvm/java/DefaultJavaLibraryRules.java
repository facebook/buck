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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.JavaBuckConfig.SourceAbiVerificationMode;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.jvm.java.JavaLibraryDescription.CoreArg;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@Value.Style(
    overshadowImplementation = true,
    init = "set*",
    visibility = Value.Style.ImplementationVisibility.PACKAGE)
public abstract class DefaultJavaLibraryRules {
  public interface DefaultJavaLibraryConstructor {
    DefaultJavaLibrary newInstance(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        JarBuildStepsFactory jarBuildStepsFactory,
        SourcePathRuleFinder ruleFinder,
        Optional<SourcePath> proguardConfig,
        SortedSet<BuildRule> firstOrderPackageableDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedDeps,
        ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedProvidedDeps,
        @Nullable BuildTarget abiJar,
        @Nullable BuildTarget sourceOnlyAbiJar,
        Optional<String> mavenCoords,
        ImmutableSortedSet<BuildTarget> tests,
        boolean requiredForSourceOnlyAbi,
        UnusedDependenciesAction unusedDependenciesAction,
        Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
        @Nullable CalculateSourceAbi previousRuleInPipeline);
  }

  @org.immutables.builder.Builder.Parameter
  abstract BuildTarget getInitialBuildTarget();

  @Value.Lazy
  BuildTarget getLibraryTarget() {
    BuildTarget initialBuildTarget = getInitialBuildTarget();
    return JavaAbis.isLibraryTarget(initialBuildTarget)
        ? initialBuildTarget
        : JavaAbis.getLibraryTarget(initialBuildTarget);
  }

  @org.immutables.builder.Builder.Parameter
  abstract ProjectFilesystem getProjectFilesystem();

  @org.immutables.builder.Builder.Parameter
  abstract ToolchainProvider getToolchainProvider();

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleParams getInitialParams();

  @org.immutables.builder.Builder.Parameter
  abstract ActionGraphBuilder getActionGraphBuilder();

  @org.immutables.builder.Builder.Parameter
  abstract CellPathResolver getCellPathResolver();

  @Value.Lazy
  SourcePathRuleFinder getSourcePathRuleFinder() {
    return new SourcePathRuleFinder(getActionGraphBuilder());
  }

  @Value.Lazy
  SourcePathResolver getSourcePathResolver() {
    return DefaultSourcePathResolver.from(getSourcePathRuleFinder());
  }

  @org.immutables.builder.Builder.Parameter
  abstract ConfiguredCompilerFactory getConfiguredCompilerFactory();

  @org.immutables.builder.Builder.Parameter
  abstract UnusedDependenciesAction getUnusedDependenciesAction();

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

    return (DefaultJavaLibrary) getActionGraphBuilder().getRule(getLibraryTarget());
  }

  public BuildRule buildAbi() {
    buildAllRules();

    return getActionGraphBuilder().getRule(getInitialBuildTarget());
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
      rootmostTarget = JavaAbis.getVerifiedSourceAbiJar(rootmostTarget);
    } else if (willProduceClassAbi()) {
      rootmostTarget = JavaAbis.getClassAbiJar(rootmostTarget);
    }

    ActionGraphBuilder graphBuilder = getActionGraphBuilder();
    graphBuilder.computeIfAbsent(
        rootmostTarget,
        target -> {
          CalculateSourceAbi sourceOnlyAbiRule = buildSourceOnlyAbiRule();
          CalculateSourceAbi sourceAbiRule = buildSourceAbiRule();
          DefaultJavaLibrary libraryRule = buildLibraryRule(sourceAbiRule);
          CalculateClassAbi classAbiRule = buildClassAbiRule(libraryRule);
          CompareAbis compareAbisRule;
          if (sourceOnlyAbiRule != null) {
            compareAbisRule = buildCompareAbisRule(sourceAbiRule, sourceOnlyAbiRule);
          } else {
            compareAbisRule = buildCompareAbisRule(classAbiRule, sourceAbiRule);
          }

          if (JavaAbis.isLibraryTarget(target)) {
            return libraryRule;
          } else if (JavaAbis.isClassAbiTarget(target)) {
            return classAbiRule;
          } else if (JavaAbis.isVerifiedSourceAbiTarget(target)) {
            return compareAbisRule;
          }

          throw new AssertionError();
        });
  }

  @Nullable
  private <T extends BuildRule & CalculateAbi, U extends BuildRule & CalculateAbi>
      CompareAbis buildCompareAbisRule(@Nullable T correctAbi, @Nullable U experimentalAbi) {
    if (!willProduceCompareAbis()) {
      return null;
    }
    Preconditions.checkNotNull(correctAbi);
    Preconditions.checkNotNull(experimentalAbi);

    BuildTarget compareAbisTarget = JavaAbis.getVerifiedSourceAbiJar(getLibraryTarget());
    return getActionGraphBuilder()
        .addToIndex(
            new CompareAbis(
                compareAbisTarget,
                getProjectFilesystem(),
                getInitialParams()
                    .withDeclaredDeps(ImmutableSortedSet.of(correctAbi, experimentalAbi))
                    .withoutExtraDeps(),
                correctAbi.getSourcePathToOutput(),
                experimentalAbi.getSourcePathToOutput(),
                Preconditions.checkNotNull(getJavaBuckConfig()).getSourceAbiVerificationMode()));
  }

  @Value.Lazy
  @Nullable
  BuildTarget getAbiJar() {
    if (willProduceCompareAbis()) {
      return JavaAbis.getVerifiedSourceAbiJar(getLibraryTarget());
    } else if (willProduceSourceAbi()) {
      return JavaAbis.getSourceAbiJar(getLibraryTarget());
    } else if (willProduceClassAbi()) {
      return JavaAbis.getClassAbiJar(getLibraryTarget());
    }

    return null;
  }

  @Value.Lazy
  @Nullable
  BuildTarget getSourceOnlyAbiJar() {
    if (willProduceSourceOnlyAbi()) {
      return JavaAbis.getSourceOnlyAbiJar(getLibraryTarget());
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
            .getModernProcessors();

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
    DefaultJavaLibraryClasspaths classpaths = getClasspaths();

    UnusedDependenciesAction unusedDependenciesAction = getUnusedDependenciesAction();
    Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory = Optional.empty();

    if (unusedDependenciesAction != UnusedDependenciesAction.IGNORE) {
      ProjectFilesystem projectFilesystem = getProjectFilesystem();
      BuildTarget buildTarget = getLibraryTarget();
      SourcePathResolver sourcePathResolver = getSourcePathResolver();
      BuildRuleResolver buildRuleResolver = getActionGraphBuilder();

      unusedDependenciesFinderFactory =
          Optional.of(
              () ->
                  UnusedDependenciesFinder.of(
                      buildTarget,
                      projectFilesystem,
                      buildRuleResolver,
                      getCellPathResolver(),
                      CompilerOutputPaths.getDepFilePath(buildTarget, projectFilesystem),
                      Preconditions.checkNotNull(getDeps()),
                      sourcePathResolver,
                      unusedDependenciesAction));
    }

    DefaultJavaLibrary libraryRule =
        getConstructor()
            .newInstance(
                getLibraryTarget(),
                getProjectFilesystem(),
                getJarBuildStepsFactory(),
                getSourcePathRuleFinder(),
                getProguardConfig(),
                classpaths.getFirstOrderPackageableDeps(),
                Preconditions.checkNotNull(getDeps()).getExportedDeps(),
                Preconditions.checkNotNull(getDeps()).getProvidedDeps(),
                Preconditions.checkNotNull(getDeps()).getExportedProvidedDeps(),
                getAbiJar(),
                getSourceOnlyAbiJar(),
                getMavenCoords(),
                getTests(),
                getRequiredForSourceOnlyAbi(),
                unusedDependenciesAction,
                unusedDependenciesFinderFactory,
                sourceAbiRule);

    getActionGraphBuilder().addToIndex(libraryRule);
    return libraryRule;
  }

  private boolean getRequiredForSourceOnlyAbi() {
    return getArgs() != null && getArgs().getRequiredForSourceOnlyAbi();
  }

  @Nullable
  private CalculateSourceAbi buildSourceOnlyAbiRule() {
    if (!willProduceSourceOnlyAbi()) {
      return null;
    }

    JarBuildStepsFactory jarBuildStepsFactory = getJarBuildStepsFactoryForSourceOnlyAbi();

    BuildTarget sourceAbiTarget = JavaAbis.getSourceOnlyAbiJar(getLibraryTarget());
    return getActionGraphBuilder()
        .addToIndex(
            new CalculateSourceAbi(
                sourceAbiTarget,
                getProjectFilesystem(),
                jarBuildStepsFactory,
                getSourcePathRuleFinder()));
  }

  @Nullable
  private CalculateSourceAbi buildSourceAbiRule() {
    if (!willProduceSourceAbi()) {
      return null;
    }

    JarBuildStepsFactory jarBuildStepsFactory = getJarBuildStepsFactory();

    BuildTarget sourceAbiTarget = JavaAbis.getSourceAbiJar(getLibraryTarget());
    return getActionGraphBuilder()
        .addToIndex(
            new CalculateSourceAbi(
                sourceAbiTarget,
                getProjectFilesystem(),
                jarBuildStepsFactory,
                getSourcePathRuleFinder()));
  }

  @Nullable
  private CalculateClassAbi buildClassAbiRule(DefaultJavaLibrary libraryRule) {
    if (!willProduceClassAbi()) {
      return null;
    }

    BuildTarget classAbiTarget = JavaAbis.getClassAbiJar(getLibraryTarget());
    return getActionGraphBuilder()
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
    return DefaultJavaLibraryClasspaths.builder(getActionGraphBuilder())
        .setBuildRuleParams(getInitialParams())
        .setDeps(Preconditions.checkNotNull(getDeps()))
        .setCompileAgainstLibraryType(getCompileAgainstLibraryType())
        .build();
  }

  @Value.Lazy
  DefaultJavaLibraryClasspaths getClasspathsForSourceOnlyAbi() {
    return getClasspaths().getSourceOnlyAbiClasspaths();
  }

  @Value.Lazy
  CompileToJarStepFactory getConfiguredCompiler() {
    return getConfiguredCompilerFactory()
        .configure(getArgs(), getJavacOptions(), getActionGraphBuilder(), getToolchainProvider());
  }

  @Value.Lazy
  CompileToJarStepFactory getConfiguredCompilerForSourceOnlyAbi() {
    return getConfiguredCompilerFactory()
        .configure(
            getArgs(),
            getJavacOptionsForSourceOnlyAbi(),
            getActionGraphBuilder(),
            getToolchainProvider());
  }

  @Value.Lazy
  JavacOptions getJavacOptionsForSourceOnlyAbi() {
    JavacOptions javacOptions = getJavacOptions();
    return javacOptions.withAnnotationProcessingParams(
        abiProcessorsOnly(javacOptions.getAnnotationProcessingParams()));
  }

  private AnnotationProcessingParams abiProcessorsOnly(
      AnnotationProcessingParams annotationProcessingParams) {
    return annotationProcessingParams.withAbiProcessorsOnly();
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
        getLibraryTarget(),
        getConfiguredCompiler(),
        getSrcs(),
        getResources(),
        getResourcesParameters(),
        getManifestFile(),
        getPostprocessClassesCommands(),
        getConfiguredCompilerFactory().trackClassUsage(getJavacOptions()),
        getJavacOptions().trackJavacPhaseEvents(),
        getClassesToRemoveFromJar(),
        getAbiGenerationMode(),
        getAbiCompatibilityMode(),
        classpaths.getDependencyInfos(),
        getRequiredForSourceOnlyAbi());
  }

  @Value.Lazy
  JarBuildStepsFactory getJarBuildStepsFactoryForSourceOnlyAbi() {
    DefaultJavaLibraryClasspaths classpaths = getClasspathsForSourceOnlyAbi();
    return new JarBuildStepsFactory(
        getLibraryTarget(),
        getConfiguredCompilerForSourceOnlyAbi(),
        getSrcs(),
        getResources(),
        getResourcesParameters(),
        getManifestFile(),
        getPostprocessClassesCommands(),
        getConfiguredCompilerFactory().trackClassUsage(getJavacOptions()),
        getJavacOptions().trackJavacPhaseEvents(),
        getClassesToRemoveFromJar(),
        getAbiGenerationMode(),
        getAbiCompatibilityMode(),
        classpaths.getDependencyInfos(),
        getRequiredForSourceOnlyAbi());
  }

  private ResourcesParameters getResourcesParameters() {
    return ResourcesParameters.create(
        getProjectFilesystem(), getSourcePathRuleFinder(), getResources(), getResourcesRoot());
  }

  private static UnusedDependenciesAction getUnusedDependenciesAction(
      @Nullable JavaBuckConfig javaBuckConfig, @Nullable JavaLibraryDescription.CoreArg args) {
    if (args != null && args.getOnUnusedDependencies().isPresent()) {
      return args.getOnUnusedDependencies().get();
    }
    if (javaBuckConfig == null) {
      return UnusedDependenciesAction.IGNORE;
    }
    return javaBuckConfig.getUnusedDependenciesAction();
  }

  @org.immutables.builder.Builder.AccessibleFields
  public static class Builder extends ImmutableDefaultJavaLibraryRules.Builder {
    public Builder(
        BuildTarget initialBuildTarget,
        ProjectFilesystem projectFilesystem,
        ToolchainProvider toolchainProvider,
        BuildRuleParams initialParams,
        ActionGraphBuilder graphBuilder,
        CellPathResolver cellPathResolver,
        ConfiguredCompilerFactory configuredCompilerFactory,
        @Nullable JavaBuckConfig javaBuckConfig,
        @Nullable JavaLibraryDescription.CoreArg args) {
      super(
          initialBuildTarget,
          projectFilesystem,
          toolchainProvider,
          initialParams,
          graphBuilder,
          cellPathResolver,
          configuredCompilerFactory,
          getUnusedDependenciesAction(javaBuckConfig, args),
          javaBuckConfig,
          args);

      this.actionGraphBuilder = graphBuilder;

      if (args != null) {
        setSrcs(args.getSrcs())
            .setResources(args.getResources())
            .setResourcesRoot(args.getResourcesRoot())
            .setProguardConfig(args.getProguardConfig())
            .setPostprocessClassesCommands(args.getPostprocessClassesCommands())
            .setDeps(JavaLibraryDeps.newInstance(args, graphBuilder, configuredCompilerFactory))
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
      return deps;
    }
  }
}
