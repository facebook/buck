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
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTarget;
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
        SortedSet<BuildRule> firstOrderPackageableDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedDeps,
        ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
        @Nullable BuildTarget abiJar,
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
                  CalculateClassAbi classAbi = Preconditions.checkNotNull(getClassAbiRule());
                  CalculateSourceAbi sourceAbi = Preconditions.checkNotNull(getSourceAbiRule());

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

  private boolean willProduceAbiJar() {
    return !getSrcs().isEmpty() || !getResources().isEmpty() || getManifestFile().isPresent();
  }

  @Value.Lazy
  AbiGenerationMode getAbiGenerationMode() {
    AbiGenerationMode result =
        Preconditions.checkNotNull(getJavaBuckConfig()).getAbiGenerationMode();

    if (result == AbiGenerationMode.CLASS) {
      return result;
    }

    if (!shouldBuildSourceAbi()) {
      return AbiGenerationMode.CLASS;
    }

    return result;
  }

  private boolean willProduceSourceAbi() {
    return willProduceAbiJar() && getAbiGenerationMode().isSourceAbi();
  }

  private boolean willProduceClassAbi() {
    return willProduceAbiJar() && (!willProduceSourceAbi() || willProduceCompareAbis());
  }

  private boolean willProduceCompareAbis() {
    return willProduceSourceAbi()
        && getJavaBuckConfig() != null
        && getJavaBuckConfig().getSourceAbiVerificationMode()
            != JavaBuckConfig.SourceAbiVerificationMode.OFF;
  }

  private boolean shouldBuildSourceAbi() {
    return getConfiguredCompilerFactory().shouldGenerateSourceAbi()
        && !getSrcs().isEmpty()
        && getPostprocessClassesCommands().isEmpty();
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
                  CalculateSourceAbi sourceAbiRule = getSourceAbiRule();
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
                              getClasspaths().getFirstOrderPackageableDeps(),
                              Preconditions.checkNotNull(getDeps()).getExportedDeps(),
                              Preconditions.checkNotNull(getDeps()).getProvidedDeps(),
                              getAbiJar(),
                              getMavenCoords(),
                              getTests(),
                              getRequiredForSourceOnlyAbi());

                  if (sourceAbiRule != null) {
                    libraryRule.setSourceAbi(sourceAbiRule);
                  }

                  return libraryRule;
                });
  }

  private boolean getRequiredForSourceOnlyAbi() {
    return getArgs() != null && getArgs().getRequiredForSourceOnlyAbi();
  }

  @Value.Lazy
  SourceOnlyAbiRuleInfo getSourceOnlyAbiRuleInfo() {
    return new DefaultSourceOnlyAbiRuleInfo(getLibraryTarget(), getRequiredForSourceOnlyAbi());
  }

  @Nullable
  private CalculateSourceAbi getSourceAbiRule() {
    if (!willProduceSourceAbi()) {
      return null;
    }

    return (CalculateSourceAbi)
        getBuildRuleResolver()
            .computeIfAbsent(
                HasJavaAbi.getSourceAbiJar(getLibraryTarget()),
                abiTarget ->
                    new CalculateSourceAbi(
                        abiTarget,
                        getProjectFilesystem(),
                        getFinalBuildDeps(),
                        getSourcePathRuleFinder(),
                        getJarBuildStepsFactory()));
  }

  @Nullable
  private CalculateClassAbi getClassAbiRule() {
    if (!willProduceClassAbi()) {
      return null;
    }

    return (CalculateClassAbi)
        getBuildRuleResolver()
            .computeIfAbsent(
                HasJavaAbi.getClassAbiJar(getLibraryTarget()),
                abiTarget ->
                    CalculateClassAbi.of(
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
  DefaultJavaLibraryClasspaths getClasspaths() {
    return DefaultJavaLibraryClasspaths.builder(getBuildRuleResolver())
        .setBuildRuleParams(getInitialParams())
        .setConfiguredCompiler(getConfiguredCompiler())
        .setDeps(Preconditions.checkNotNull(getDeps()))
        .setShouldCompileAgainstAbis(getConfiguredCompilerFactory().shouldCompileAgainstAbis())
        .build();
  }

  @Value.Lazy
  ConfiguredCompiler getConfiguredCompiler() {
    return getConfiguredCompilerFactory()
        .configure(getArgs(), getJavacOptions(), getBuildRuleResolver());
  }

  @Value.Lazy
  ImmutableSortedSet<BuildRule> getFinalBuildDeps() {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();

    DefaultJavaLibraryClasspaths classpaths = getClasspaths();
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

    if (!getConfiguredCompilerFactory().shouldCompileAgainstAbis()) {
      depsBuilder.addAll(classpaths.getCompileTimeClasspathFullDeps());
    }

    return depsBuilder.build();
  }

  @Value.Lazy
  JarBuildStepsFactory getJarBuildStepsFactory() {
    DefaultJavaLibraryClasspaths classpaths = getClasspaths();
    return new JarBuildStepsFactory(
        getProjectFilesystem(),
        getSourcePathRuleFinder(),
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
        getSourceOnlyAbiRuleInfo());
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
        if (args.getGenerateSourceOnlyAbi().isPresent()) {
          setSourceOnlyAbisAllowed(args.getGenerateSourceOnlyAbi().get());
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
