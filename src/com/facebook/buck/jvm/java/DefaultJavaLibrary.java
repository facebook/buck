/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.step.Step;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUCK</code>:
 *
 * <pre>
 * java_library(
 *   name = 'feed',
 *   srcs = [
 *     'FeedStoryRenderer.java',
 *   ],
 *   deps = [
 *     '//src/com/facebook/feed/model:model',
 *     '//third-party/java/guava:guava',
 *   ],
 * )
 * </pre>
 *
 * Then this would compile {@code FeedStoryRenderer.java} against Guava and the classes generated
 * from the {@code //src/com/facebook/feed/model:model} rule.
 */
public class DefaultJavaLibrary extends AbstractBuildRule
    implements JavaLibrary,
        HasClasspathEntries,
        HasClasspathDeps,
        ExportDependencies,
        InitializableFromDisk<JavaLibrary.Data>,
        AndroidPackageable,
        MaybeRequiredForSourceOnlyAbi,
        SupportsInputBasedRuleKey,
        SupportsDependencyFileRuleKey,
        SupportsPipelining<JavacPipelineState>,
        JavaLibraryWithTests {

  @AddToRuleKey private final JarBuildStepsFactory jarBuildStepsFactory;
  @AddToRuleKey private final Optional<String> mavenCoords;
  private final JarContentsSupplier outputJarContentsSupplier;
  @Nullable private final BuildTarget abiJar;
  @Nullable private final BuildTarget sourceOnlyAbiJar;
  @AddToRuleKey private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey private final boolean requiredForSourceOnlyAbi;
  @AddToRuleKey private final UnusedDependenciesAction unusedDependenciesAction;
  private final Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory;

  // This is automatically added to the rule key by virtue of being returned from getBuildDeps.
  private final BuildDeps buildDeps;

  // It's very important that these deps are non-ABI rules, even if compiling against ABIs is turned
  // on. This is because various methods in this class perform dependency traversal that rely on
  // these deps being represented as their full-jar dependency form.
  private final SortedSet<BuildRule> firstOrderPackageableDeps;
  private final ImmutableSortedSet<BuildRule> fullJarExportedDeps;
  private final ImmutableSortedSet<BuildRule> fullJarProvidedDeps;
  private final ImmutableSortedSet<BuildRule> fullJarExportedProvidedDeps;

  private final Supplier<ImmutableSet<SourcePath>> outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<SourcePath>> transitiveClasspathsSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;

  @Nullable private CalculateSourceAbi sourceAbi;

  public static DefaultJavaLibraryRules.Builder rulesBuilder(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellPathResolver,
      ConfiguredCompilerFactory compilerFactory,
      @Nullable JavaBuckConfig javaBuckConfig,
      @Nullable JavaLibraryDescription.CoreArg args) {
    return new DefaultJavaLibraryRules.Builder(
        buildTarget,
        projectFilesystem,
        toolchainProvider,
        params,
        graphBuilder,
        cellPathResolver,
        compilerFactory,
        javaBuckConfig,
        args);
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  protected DefaultJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildDeps buildDeps,
      SourcePathResolver resolver,
      JarBuildStepsFactory jarBuildStepsFactory,
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
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory) {
    super(buildTarget, projectFilesystem);
    this.buildDeps = buildDeps;
    this.jarBuildStepsFactory = jarBuildStepsFactory;
    this.unusedDependenciesAction = unusedDependenciesAction;
    this.unusedDependenciesFinderFactory = unusedDependenciesFinderFactory;

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    validateExportedDepsType(buildTarget, fullJarExportedDeps);
    validateExportedDepsType(buildTarget, fullJarExportedProvidedDeps);

    Sets.SetView<BuildRule> missingExports =
        Sets.difference(fullJarExportedDeps, firstOrderPackageableDeps);
    // Exports should have been copied over to declared before invoking this constructor
    Preconditions.checkState(missingExports.isEmpty());

    this.proguardConfig = proguardConfig;
    this.firstOrderPackageableDeps = firstOrderPackageableDeps;
    this.fullJarExportedDeps = fullJarExportedDeps;
    this.fullJarProvidedDeps = fullJarProvidedDeps;
    this.fullJarExportedProvidedDeps = fullJarExportedProvidedDeps;
    this.mavenCoords = mavenCoords;
    this.tests = tests;
    this.requiredForSourceOnlyAbi = requiredForSourceOnlyAbi;

    this.outputJarContentsSupplier = new JarContentsSupplier(resolver, getSourcePathToOutput());
    this.abiJar = abiJar;
    this.sourceOnlyAbiJar = sourceOnlyAbiJar;

    this.outputClasspathEntriesSupplier =
        MoreSuppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getOutputClasspathJars(
                    DefaultJavaLibrary.this, sourcePathForOutputJar()));

    this.transitiveClasspathsSupplier =
        MoreSuppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getClasspathsFromLibraries(
                    getTransitiveClasspathDeps()));

    this.transitiveClasspathDepsSupplier =
        MoreSuppliers.memoize(
            () -> JavaLibraryClasspathProvider.getTransitiveClasspathDeps(DefaultJavaLibrary.this));

    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  private static void validateExportedDepsType(
      BuildTarget buildTarget, ImmutableSortedSet<BuildRule> exportedDeps) {
    for (BuildRule dep : exportedDeps) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            buildTarget
                + ": exported dep "
                + dep.getBuildTarget()
                + " ("
                + dep.getType()
                + ") "
                + "must be a type of java library.");
      }
    }
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public boolean getRequiredForSourceOnlyAbi() {
    return requiredForSourceOnlyAbi;
  }

  public void setSourceAbi(CalculateSourceAbi sourceAbi) {
    this.sourceAbi = sourceAbi;
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return Optional.ofNullable(jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget()));
  }

  public static Path getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            CompilerParameters.getOutputJarDirPath(target, filesystem),
            target.getShortNameAndFlavorPostfix()));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return jarBuildStepsFactory.getSources();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return jarBuildStepsFactory.getSources();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return jarBuildStepsFactory.getResources();
  }

  @Override
  public Set<BuildRule> getDepsForTransitiveClasspathEntries() {
    return firstOrderPackageableDeps;
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return transitiveClasspathsSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    ImmutableSet.Builder<SourcePath> builder = ImmutableSet.builder();

    // Add any exported deps.
    for (BuildRule exported : getExportedDeps()) {
      if (exported instanceof JavaLibrary) {
        builder.addAll(((JavaLibrary) exported).getImmediateClasspaths());
      }
    }

    // Add ourselves to the classpath if there's a jar to be built.
    Optional<SourcePath> sourcePathForOutputJar = sourcePathForOutputJar();
    if (sourcePathForOutputJar.isPresent()) {
      builder.add(sourcePathForOutputJar.get());
    }

    return builder.build();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return outputClasspathEntriesSupplier.get();
  }

  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return jarBuildStepsFactory.getCompileTimeClasspathSourcePaths();
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return CompilerParameters.getAnnotationPath(getProjectFilesystem(), getBuildTarget());
  }

  @Override
  public SortedSet<BuildRule> getExportedDeps() {
    return fullJarExportedDeps;
  }

  @Override
  public SortedSet<BuildRule> getExportedProvidedDeps() {
    return fullJarExportedProvidedDeps;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under {@link BuckPaths#getScratchDir()}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        jarBuildStepsFactory.getBuildStepsForLibraryJar(
            context, buildableContext, getBuildTarget()));

    unusedDependenciesFinderFactory.ifPresent(factory -> steps.add(factory.create()));

    return steps.build();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return outputJarContentsSupplier.get();
  }

  @Override
  public boolean jarContains(String path) {
    return outputJarContentsSupplier.jarContains(path);
  }

  /** Instructs this rule to report the ABI it has on disk as its current ABI. */
  @Override
  public JavaLibrary.Data initializeFromDisk() throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    outputJarContentsSupplier.load();
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), getProjectFilesystem());
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public final Optional<BuildTarget> getAbiJar() {
    return Optional.ofNullable(abiJar);
  }

  @Override
  public Optional<BuildTarget> getSourceOnlyAbiJar() {
    return Optional.ofNullable(sourceOnlyAbiJar);
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    return jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    // TODO(jkeljo): Subtracting out provided deps is probably not the right behavior (we don't
    // do it when assembling the contents of a java_binary), but it is long-standing and projects
    // are depending upon it. The long term direction should be that we either require that
    // a dependency be present in only one list or define a strict order of precedence among
    // the lists (exported overrides deps overrides exported_provided overrides provided.)
    return AndroidPackageableCollector.getPackageableRules(
        ImmutableSortedSet.copyOf(
            Sets.difference(
                firstOrderPackageableDeps,
                Sets.union(fullJarProvidedDeps, fullJarExportedProvidedDeps))));
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    SourcePath output = getSourcePathToOutput();
    if (output != null) {
      collector.addClasspathEntry(this, output);
    }
    if (proguardConfig.isPresent()) {
      collector.addProguardConfig(getBuildTarget(), proguardConfig.get());
    }
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return jarBuildStepsFactory.useDependencyFileRuleKeys();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return jarBuildStepsFactory.getCoveredByDepFilePredicate(pathResolver);
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return jarBuildStepsFactory.getExistenceOfInterestPredicate(pathResolver);
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, cellPathResolver, getBuildTarget());
  }

  @Override
  public boolean useRulePipelining() {
    return jarBuildStepsFactory.useRulePipelining();
  }

  @Override
  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return jarBuildStepsFactory;
  }

  @Nullable
  @Override
  public SupportsPipelining<JavacPipelineState> getPreviousRuleInPipeline() {
    return sourceAbi;
  }

  @Override
  public ImmutableList<? extends Step> getPipelinedBuildSteps(
      BuildContext context, BuildableContext buildableContext, JavacPipelineState state) {
    return jarBuildStepsFactory.getPipelinedBuildStepsForLibraryJar(
        context, buildableContext, state);
  }
}
