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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.BuckPaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
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
public class DefaultJavaLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements JavaLibrary,
        HasClasspathEntries,
        ExportDependencies,
        InitializableFromDisk<JavaLibrary.Data>,
        AndroidPackageable,
        SupportsInputBasedRuleKey,
        SupportsDependencyFileRuleKey,
        JavaLibraryWithTests {

  private static final Path METADATA_DIR = Paths.get("META-INF");

  @AddToRuleKey private final JarBuildStepsFactory jarBuildStepsFactory;
  @AddToRuleKey private final Optional<String> mavenCoords;
  private final JarContentsSupplier outputJarContentsSupplier;
  @Nullable private final BuildTarget abiJar;
  @AddToRuleKey private final Optional<SourcePath> proguardConfig;

  // It's very important that these deps are non-ABI rules, even if compiling against ABIs is turned
  // on. This is because various methods in this class perform dependency traversal that rely on
  // these deps being represented as their full-jar dependency form.
  private final SortedSet<BuildRule> fullJarDeclaredDeps;
  private final ImmutableSortedSet<BuildRule> fullJarExportedDeps;
  private final ImmutableSortedSet<BuildRule> fullJarProvidedDeps;

  private final Supplier<ImmutableSet<SourcePath>> outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<SourcePath>> transitiveClasspathsSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;

  public static DefaultJavaLibraryBuilder builder(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      JavaBuckConfig javaBuckConfig) {
    return new DefaultJavaLibraryBuilder(
        targetGraph,
        buildTarget,
        projectFilesystem,
        params,
        buildRuleResolver,
        cellRoots,
        javaBuckConfig);
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  protected DefaultJavaLibrary(
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathResolver resolver,
      JarBuildStepsFactory jarBuildStepsFactory,
      Optional<SourcePath> proguardConfig,
      SortedSet<BuildRule> fullJarDeclaredDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedDeps,
      ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
      @Nullable BuildTarget abiJar,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests) {
    super(buildTarget, projectFilesystem, params);
    this.jarBuildStepsFactory = jarBuildStepsFactory;

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    for (BuildRule dep : fullJarExportedDeps) {
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

    this.proguardConfig = proguardConfig;
    this.fullJarDeclaredDeps = fullJarDeclaredDeps;
    this.fullJarExportedDeps = fullJarExportedDeps;
    this.fullJarProvidedDeps = fullJarProvidedDeps;
    this.mavenCoords = mavenCoords;
    this.tests = tests;

    this.outputJarContentsSupplier = new JarContentsSupplier(resolver, getSourcePathToOutput());
    this.abiJar = abiJar;

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getOutputClasspathJars(
                    DefaultJavaLibrary.this, sourcePathForOutputJar()));

    this.transitiveClasspathsSupplier =
        Suppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getClasspathsFromLibraries(
                    getTransitiveClasspathDeps()));

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            () -> JavaLibraryClasspathProvider.getTransitiveClasspathDeps(DefaultJavaLibrary.this));

    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  public static Path getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "lib__%s__output");
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return Optional.ofNullable(jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget()));
  }

  static Path getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target, filesystem), target.getShortNameAndFlavorPostfix()));
  }

  /**
   * @return directory path relative to the project root where .class files will be generated. The
   *     return value does not end with a slash.
   */
  public static Path getClassesDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, target, "lib__%s__classes");
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
    return Sets.union(fullJarDeclaredDeps, fullJarExportedDeps);
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
    return JavaLibraryRules.getAnnotationPath(getProjectFilesystem(), getBuildTarget());
  }

  @Override
  public SortedSet<BuildRule> getExportedDeps() {
    return fullJarExportedDeps;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under {@link BuckPaths#getScratchDir()}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return jarBuildStepsFactory.getBuildStepsForLibraryJar(
        context, buildableContext, getBuildTarget());
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return outputJarContentsSupplier.get();
  }

  /** Instructs this rule to report the ABI it has on disk as its current ABI. */
  @Override
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    outputJarContentsSupplier.load();
    return JavaLibraryRules.initializeFromDisk(
        getBuildTarget(), getProjectFilesystem(), onDiskBuildInfo);
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
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    return jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(
        ImmutableSortedSet.copyOf(
            Sets.difference(
                Sets.union(fullJarDeclaredDeps, fullJarExportedDeps), fullJarProvidedDeps)));
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
    // Annotation processors might enumerate all files under a certain path and then generate
    // code based on that list (without actually reading the files), making the list of files
    // itself a used dependency that must be part of the dependency-based key. We don't
    // currently have the instrumentation to detect such enumeration perfectly, but annotation
    // processors are most commonly looking for files under META-INF, so as a stopgap we add
    // the listing of META-INF to the rule key.
    return (SourcePath path) ->
        (path instanceof ArchiveMemberSourcePath)
            && pathResolver
                .getRelativeArchiveMemberPath(path)
                .getMemberPath()
                .startsWith(METADATA_DIR);
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, cellPathResolver, getBuildTarget());
  }
}
