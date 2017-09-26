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
  boolean getSourceAbisAllowed() {
    return true;
  }

  abstract JavacOptions getJavacOptions();

  @Nullable
  abstract JavaLibraryDeps getDeps();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaLibraryDescription.CoreArg getArgs();

  public DefaultJavaLibrary buildLibrary() {
    return getLibraryRule();
  }

  public BuildRule buildAbi() {
    BuildRule result = getCompareAbisRule();
    if (result == null) {
      result = getClassAbiRule();
    }
    if (result == null) {
      result = getSourceAbiRule();
    }

    return Preconditions.checkNotNull(result);
  }

  @Nullable
  private CompareAbis getCompareAbisRule() {
    if (!willProduceCompareAbis()) {
      return null;
    }

    return (CompareAbis)
        getBuildRuleResolver()
            .computeIfAbsent(
                HasJavaAbi.getVerifiedSourceAbiJar(getLibraryTarget()),
                target -> {
                  CalculateAbiFromClasses classAbi = Preconditions.checkNotNull(getClassAbiRule());
                  CalculateAbiFromSource sourceAbi = Preconditions.checkNotNull(getSourceAbiRule());

                  return new CompareAbis(
                      getInitialBuildTarget(),
                      getProjectFilesystem(),
                      getInitialParams()
                          .withDeclaredDeps(ImmutableSortedSet.of(classAbi, sourceAbi))
                          .withoutExtraDeps(),
                      getSourcePathResolver(),
                      classAbi.getSourcePathToOutput(),
                      sourceAbi.getSourcePathToOutput(),
                      Preconditions.checkNotNull(getJavaBuckConfig())
                          .getSourceAbiVerificationMode());
                });
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

  private boolean willProduceOutputJar() {
    return !getSrcs().isEmpty() || !getResources().isEmpty() || getManifestFile().isPresent();
  }

  private boolean willProduceSourceAbi() {
    return willProduceOutputJar() && shouldBuildAbiFromSource();
  }

  private boolean willProduceClassAbi() {
    return willProduceOutputJar() && (!willProduceSourceAbi() || willProduceCompareAbis());
  }

  private boolean willProduceCompareAbis() {
    return willProduceSourceAbi()
        && getJavaBuckConfig() != null
        && getJavaBuckConfig().getSourceAbiVerificationMode()
            != JavaBuckConfig.SourceAbiVerificationMode.OFF;
  }

  private boolean shouldBuildAbiFromSource() {
    return isCompilingJava()
        && !getSrcs().isEmpty()
        && sourceAbisEnabled()
        && getSourceAbisAllowed()
        && getPostprocessClassesCommands().isEmpty();
  }

  private boolean isCompilingJava() {
    return getConfiguredCompiler() instanceof JavacToJarStepFactory;
  }

  private boolean sourceAbisEnabled() {
    return getJavaBuckConfig() != null && getJavaBuckConfig().shouldGenerateAbisFromSource();
  }

  private DefaultJavaLibrary getLibraryRule() {
    return (DefaultJavaLibrary)
        getBuildRuleResolver()
            .computeIfAbsent(
                getLibraryTarget(),
                target -> {
                  ImmutableSortedSet.Builder<BuildRule> buildDepsBuilder =
                      ImmutableSortedSet.naturalOrder();

                  buildDepsBuilder.addAll(getFinalBuildDeps());
                  CalculateAbiFromSource sourceAbiRule = getSourceAbiRule();
                  if (sourceAbiRule != null) {
                    buildDepsBuilder.add(sourceAbiRule);
                  }

                  DefaultJavaLibrary libraryRule =
                      getConstructor()
                          .newInstance(
                              getInitialBuildTarget(),
                              getProjectFilesystem(),
                              buildDepsBuilder.build(),
                              getSourcePathResolver(),
                              getJarBuildStepsFactory(),
                              getProguardConfig(),
                              getFinalFullJarDeclaredDeps(),
                              Preconditions.checkNotNull(getDeps()).getExportedDeps(),
                              Preconditions.checkNotNull(getDeps()).getProvidedDeps(),
                              getAbiJar(),
                              getMavenCoords(),
                              getTests(),
                              getRequiredForSourceAbi());

                  if (sourceAbiRule != null) {
                    libraryRule.setSourceAbi(sourceAbiRule);
                  }

                  return libraryRule;
                });
  }

  private boolean getRequiredForSourceAbi() {
    return getArgs() != null && getArgs().getRequiredForSourceAbi();
  }

  @Nullable
  private CalculateAbiFromSource getSourceAbiRule() {
    if (!willProduceSourceAbi()) {
      return null;
    }

    return (CalculateAbiFromSource)
        getBuildRuleResolver()
            .computeIfAbsent(
                HasJavaAbi.getSourceAbiJar(getLibraryTarget()),
                abiTarget ->
                    new CalculateAbiFromSource(
                        abiTarget,
                        getProjectFilesystem(),
                        getFinalBuildDeps(),
                        getSourcePathRuleFinder(),
                        getJarBuildStepsFactory()));
  }

  @Nullable
  private CalculateAbiFromClasses getClassAbiRule() {
    if (!willProduceClassAbi()) {
      return null;
    }

    return (CalculateAbiFromClasses)
        getBuildRuleResolver()
            .computeIfAbsent(
                HasJavaAbi.getClassAbiJar(getLibraryTarget()),
                abiTarget ->
                    CalculateAbiFromClasses.of(
                        abiTarget,
                        getSourcePathRuleFinder(),
                        getProjectFilesystem(),
                        getInitialParams(),
                        Preconditions.checkNotNull(getLibraryRule().getSourcePathToOutput()),
                        getJavaBuckConfig() != null
                            && getJavaBuckConfig().getSourceAbiVerificationMode()
                                != JavaBuckConfig.SourceAbiVerificationMode.OFF));
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getFinalFullJarDeclaredDeps() {
    return ImmutableSortedSet.copyOf(
        Iterables.concat(
            Preconditions.checkNotNull(getDeps()).getDeps(),
            getConfiguredCompiler().getDeclaredDeps(getSourcePathRuleFinder())));
  }

  private ImmutableSortedSet<SourcePath> getFinalCompileTimeClasspathSourcePaths() {
    ImmutableSortedSet<BuildRule> buildRules =
        getConfiguredCompilerFactory().compileAgainstAbis()
            ? getCompileTimeClasspathAbiDeps()
            : getCompileTimeClasspathFullDeps();

    return buildRules
        .stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    return getCompileTimeClasspathUnfilteredFullDeps()
        .stream()
        .filter(dep -> dep instanceof HasJavaAbi)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
    return JavaLibraryRules.getAbiRules(getBuildRuleResolver(), getCompileTimeClasspathFullDeps());
  }

  @Value.Lazy
  ZipArchiveDependencySupplier getAbiClasspath() {
    return new ZipArchiveDependencySupplier(
        getSourcePathRuleFinder(),
        getCompileTimeClasspathAbiDeps()
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()));
  }

  @Value.Lazy
  ConfiguredCompiler getConfiguredCompiler() {
    return getConfiguredCompilerFactory()
        .configure(getArgs(), getJavacOptions(), getBuildRuleResolver());
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getFinalBuildDeps() {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();

    depsBuilder
        // We always need the non-classpath deps, whether directly specified or specified via
        // query
        .addAll(
            Sets.difference(getInitialParams().getBuildDeps(), getCompileTimeClasspathFullDeps()))
        .addAll(
            Sets.difference(
                getCompileTimeClasspathUnfilteredFullDeps(), getCompileTimeClasspathFullDeps()))
        // It's up to the compiler to use an ABI jar for these deps if appropriate, so we can
        // add them unconditionally
        .addAll(getConfiguredCompiler().getBuildDeps(getSourcePathRuleFinder()))
        // We always need the ABI deps (at least for rulekey computation)
        // TODO(jkeljo): It's actually incorrect to use ABIs for rulekey computation for languages
        // that can't compile against them. Generally the reason they can't compile against ABIs
        // is that the ABI generation for that language isn't fully correct.
        .addAll(getCompileTimeClasspathAbiDeps());

    if (!getConfiguredCompilerFactory().compileAgainstAbis()) {
      depsBuilder.addAll(getCompileTimeClasspathFullDeps());
    }

    return depsBuilder.build();
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    Iterable<BuildRule> firstOrderDeps =
        Iterables.concat(
            getFinalFullJarDeclaredDeps(), Preconditions.checkNotNull(getDeps()).getProvidedDeps());

    ImmutableSortedSet<BuildRule> rulesExportedByDependencies =
        BuildRules.getExportedRules(firstOrderDeps);

    return RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Value.Lazy
  JarBuildStepsFactory getJarBuildStepsFactory() {
    return new JarBuildStepsFactory(
        getProjectFilesystem(),
        getSourcePathRuleFinder(),
        getConfiguredCompiler(),
        getSrcs(),
        getResources(),
        getResourcesRoot(),
        getManifestFile(),
        getPostprocessClassesCommands(),
        getAbiClasspath(),
        getConfiguredCompilerFactory().trackClassUsage(getJavacOptions()),
        getFinalCompileTimeClasspathSourcePaths(),
        getClassesToRemoveFromJar(),
        getRequiredForSourceAbi());
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
        if (args.getGenerateAbiFromSource().isPresent()) {
          setSourceAbisAllowed(args.getGenerateAbiFromSource().get());
        }

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
