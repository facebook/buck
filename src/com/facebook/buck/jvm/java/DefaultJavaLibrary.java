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

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.BuckPaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUCK</code>:
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
 * Then this would compile {@code FeedStoryRenderer.java} against Guava and the classes generated
 * from the {@code //src/com/facebook/feed/model:model} rule.
 */
public class DefaultJavaLibrary extends AbstractBuildRule
    implements JavaLibrary, HasClasspathEntries, ExportDependencies,
    InitializableFromDisk<JavaLibrary.Data>, AndroidPackageable,
    SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey, JavaLibraryWithTests {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;
  @AddToRuleKey
  private final Optional<SourcePath> manifestFile;
  @AddToRuleKey
  private final Optional<String> mavenCoords;
  private final Optional<Path> outputJar;
  @AddToRuleKey
  private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey
  private final ImmutableList<String> postprocessClassesCommands;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final ImmutableSortedSet<BuildRule> providedDeps;
  // Some classes need to override this when enhancing deps (see AndroidLibrary).
  private final ImmutableSet<Path> additionalClasspathEntries;
  private final Supplier<ImmutableSet<Path>>
      outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<Path>>
      transitiveClasspathsSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final SourcePath abiJar;
  private final boolean trackClassUsage;
  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final JarArchiveDependencySupplier abiClasspath;
  private final ImmutableSortedSet<BuildRule> deps;
  @Nullable private Path depFileOutputPath;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final Optional<Path> generatedSourceFolder;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSet<Pattern> classesToRemoveFromJar;

  @AddToRuleKey
  private final CompileToJarStepFactory compileStepFactory;

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  private static final SuggestBuildRules.JarResolver JAR_RESOLVER =
      classPath -> {
        ImmutableSet.Builder<String> topLevelSymbolsBuilder = ImmutableSet.builder();
        try {
          ClassLoader loader = URLClassLoader.newInstance(
              new URL[]{classPath.toUri().toURL()},
            /* parent */ null);

          // For every class contained in that jar, check to see if the package name
          // (e.g. com.facebook.foo), the simple name (e.g. ImmutableSet) or the name
          // (e.g com.google.common.collect.ImmutableSet) is one of the missing symbols.
          for (ClassPath.ClassInfo classInfo : ClassPath.from(loader).getTopLevelClasses()) {
            topLevelSymbolsBuilder.add(classInfo.getPackageName(),
                classInfo.getSimpleName(),
                classInfo.getName());
          }
        } catch (IOException e) {
          // Since this simply is a heuristic, return an empty set if we fail to load a jar.
          return topLevelSymbolsBuilder.build();
        }
        return topLevelSymbolsBuilder.build();
      };

  public DefaultJavaLibrary(
      final BuildRuleParams params,
      SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      boolean trackClassUsage,
      ImmutableSet<Path> additionalClasspathEntries,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    this(
        params,
        resolver,
        srcs,
        resources,
        generatedSourceFolder,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        abiJar,
        trackClassUsage,
        new JarArchiveDependencySupplier(
            Suppliers.memoize(() -> JavaLibraryRules.getAbiInputs(params.getDeps())),
            params.getProjectFilesystem()),
        additionalClasspathEntries,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        mavenCoords,
        tests,
        classesToRemoveFromJar);
  }

  private DefaultJavaLibrary(
      BuildRuleParams params,
      final SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      boolean trackClassUsage,
      final JarArchiveDependencySupplier abiClasspath,
      ImmutableSet<Path> additionalClasspathEntries,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    super(
        params.appendExtraDeps(() -> resolver.filterBuildRuleInputs(abiClasspath.get())),
        resolver);
    this.compileStepFactory = compileStepFactory;

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    for (BuildRule dep : exportedDeps) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            params.getBuildTarget() + ": exported dep " +
            dep.getBuildTarget() + " (" + dep.getType() + ") " +
            "must be a type of java library.");
      }
    }

    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.proguardConfig = proguardConfig;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.exportedDeps = exportedDeps;
    this.providedDeps = providedDeps;
    this.additionalClasspathEntries = FluentIterable
        .from(additionalClasspathEntries)
        .transform(getProjectFilesystem().getAbsolutifier())
        .toSet();
    this.resourcesRoot = resourcesRoot;
    this.manifestFile = manifestFile;
    this.mavenCoords = mavenCoords;
    this.tests = tests;

    this.abiJar = abiJar;
    this.trackClassUsage = trackClassUsage;
    this.abiClasspath = abiClasspath;
    this.deps = params.getDeps();
    if (!srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget(), getProjectFilesystem()));
    } else {
      this.outputJar = Optional.absent();
    }

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(() -> JavaLibraryClasspathProvider.getOutputClasspathJars(
            DefaultJavaLibrary.this,
            getResolver(),
            sourcePathForOutputJar()));

    this.transitiveClasspathsSupplier =
        Suppliers.memoize(() -> JavaLibraryClasspathProvider.getClasspathsFromLibraries(
            getTransitiveClasspathDeps()));

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            () -> JavaLibraryClasspathProvider.getTransitiveClasspathDeps(
                DefaultJavaLibrary.this));

    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    this.generatedSourceFolder = generatedSourceFolder;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
  }

  private Path getPathToAbiOutputDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "lib__%s__abi");
  }

  public static Path getOutputJarDirPath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "lib__%s__output");
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return outputJar.transform(SourcePaths.getToBuildTargetSourcePath(getBuildTarget()));
  }

  static Path getOutputJarPath(BuildTarget target, ProjectFilesystem filesystem) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target, filesystem),
            target.getShortNameAndFlavorPostfix()));
  }

  static Path getUsedClassesFilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return getOutputJarDirPath(target, filesystem).resolve("used-classes.json");
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   *     The return value does not end with a slash.
   */
  public static Path getClassesDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, target, "lib__%s__classes");
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().deprecatedAllPaths(srcs));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return resources;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSet<Path> getTransitiveClasspaths() {
    return transitiveClasspathsSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSet<Path> getImmediateClasspaths() {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    // Add any exported deps.
    for (BuildRule exported : getExportedDeps()) {
      if (exported instanceof JavaLibrary) {
        builder.addAll(((JavaLibrary) exported).getImmediateClasspaths());
      }
    }

    // Add ourselves to the classpath if there's a jar to be built.
    Optional<SourcePath> sourcePathForOutputJar = sourcePathForOutputJar();
    if (sourcePathForOutputJar.isPresent()) {
      builder.add(getResolver().getAbsolutePath(sourcePathForOutputJar.get()));
    }

    return builder.build();
  }

  @Override
  public ImmutableSet<Path> getOutputClasspaths() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return generatedSourceFolder;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under {@link BuckPaths#getScratchDir()}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    FluentIterable<JavaLibrary> declaredClasspathDeps =
        JavaLibraryClasspathProvider.getJavaLibraryDeps(getDepsForTransitiveClasspathEntries());


    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    BuildTarget target = getBuildTarget();
    Path outputDirectory = getClassesDir(target, getProjectFilesystem());
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory));

    SuggestBuildRules suggestBuildRule =
        DefaultSuggestBuildRules.createSuggestBuildFunction(
            JAR_RESOLVER,
            declaredClasspathDeps.toSet(),
            ImmutableSet.<JavaLibrary>builder()
                .addAll(getTransitiveClasspathDeps())
                .add(this)
                .build(),
            context.getActionGraph().getNodes());

    // We don't want to add these to the declared or transitive deps, since they're only used at
    // compile time.
    Collection<Path> provided = JavaLibraryClasspathProvider.getJavaLibraryDeps(providedDeps)
        .transformAndConcat(
            new Function<JavaLibrary, Collection<Path>>() {
              @Override
              public Collection<Path> apply(JavaLibrary input) {
                return input.getOutputClasspaths();
              }
            })
        .filter(Predicates.notNull())
        .toSet();

    ProjectFilesystem projectFilesystem = getProjectFilesystem(); // NOPMD confused by lambda
    Iterable<Path> declaredClasspaths = declaredClasspathDeps.transformAndConcat(
        new Function<JavaLibrary, Iterable<Path>>() {
          @Override
          public Iterable<Path> apply(JavaLibrary input) {
            return input.getOutputClasspaths();
          }
        }).transform(projectFilesystem::resolve);
    // Only override the bootclasspath if this rule is supposed to compile Android code.
    ImmutableSortedSet<Path> declared = ImmutableSortedSet.<Path>naturalOrder()
        .addAll(declaredClasspaths)
        .addAll(additionalClasspathEntries)
        .addAll(provided)
        .build();


    // Make sure that this directory exists because ABI information will be written here.
    Step mkdir = new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToAbiOutputDir());
    steps.add(mkdir);

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }

    steps.add(
        new CopyResourcesStep(
            getProjectFilesystem(),
            getResolver(),
            target,
            resources,
            outputDirectory,
            finder));

    steps.add(
        new MakeCleanDirectoryStep(
            getProjectFilesystem(),
            getOutputJarDirPath(target, getProjectFilesystem())));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!getJavaSrcs().isEmpty()) {
      ClassUsageFileWriter usedClassesFileWriter;
      if (trackClassUsage) {
        final Path usedClassesFilePath =
            getUsedClassesFilePath(getBuildTarget(), getProjectFilesystem());
        depFileOutputPath = getProjectFilesystem().getPathForRelativePath(usedClassesFilePath);
        usedClassesFileWriter = new DefaultClassUsageFileWriter(
            usedClassesFilePath,
            new ClassUsageTracker());

        buildableContext.recordArtifact(usedClassesFilePath);
      } else {
        usedClassesFileWriter = NoOpClassUsageFileWriter.instance();
      }

      // This adds the javac command, along with any supporting commands.
      Path pathToSrcsList =
          BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__srcs");
      steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

      Path scratchDir =
          BuildTargets.getGenPath(getProjectFilesystem(), target, "lib__%s____working_directory");
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      Optional<Path> workingDirectory = Optional.of(scratchDir);

      compileStepFactory.createCompileToJarStep(
          context,
          getJavaSrcs(),
          target,
          getResolver(),
          getProjectFilesystem(),
          declared,
          outputDirectory,
          workingDirectory,
          pathToSrcsList,
          Optional.of(suggestBuildRule),
          postprocessClassesCommands,
          ImmutableSortedSet.of(outputDirectory),
          /* mainClass */ Optional.absent(),
          manifestFile.transform(getResolver().getAbsolutePathFunction()),
          outputJar.get(),
          usedClassesFileWriter,
          /* output params */
          steps,
          buildableContext,
          classesToRemoveFromJar);
    }

    Path abiJar = getOutputJarDirPath(target, getProjectFilesystem())
        .resolve(String.format("%s-abi.jar", target.getShortNameAndFlavorPostfix()));

    if (outputJar.isPresent()) {
      Path output = outputJar.get();

      // No source files, only resources
      if (getJavaSrcs().isEmpty()) {
        steps.add(
            new JarDirectoryStep(
                getProjectFilesystem(),
                output,
                ImmutableSortedSet.of(outputDirectory),
                /* mainClass */ null,
                manifestFile.transform(getResolver().getAbsolutePathFunction()).orNull(),
                true,
                classesToRemoveFromJar));
      }
      buildableContext.recordArtifact(output);

      // Calculate the ABI.
      steps.add(new CalculateAbiStep(buildableContext, getProjectFilesystem(), output, abiJar));
    } else {
      Path scratch = BuildTargets.getScratchPath(
          getProjectFilesystem(),
          target,
          String.format("%%s/%s-temp-abi.jar", target.getShortNameAndFlavorPostfix()));
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratch.getParent()));
      steps.add(new TouchStep(getProjectFilesystem(), scratch));
      steps.add(new CalculateAbiStep(buildableContext, getProjectFilesystem(), scratch, abiJar));
    }

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    return steps.build();
  }

  /**
   * Instructs this rule to report the ABI it has on disk as its current ABI.
   */
  @Override
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    return JavaLibraryRules.initializeFromDisk(
        getBuildTarget(),
        getProjectFilesystem(),
        onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return outputJar.isPresent() ? Optional.of(abiJar) : Optional.absent();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return outputJar.orNull();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(ImmutableSortedSet.copyOf(
            Sets.difference(
                Sets.union(getDeclaredDeps(), exportedDeps),
                providedDeps)));
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (outputJar.isPresent()) {
      collector.addClasspathEntry(
          this,
          new BuildTargetSourcePath(getBuildTarget(), outputJar.get()));
    }
    if (proguardConfig.isPresent()) {
      collector.addProguardConfig(getBuildTarget(), proguardConfig.get());
    }
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return !getJavaSrcs().isEmpty() && trackClassUsage;
  }

  @Override
  public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() throws IOException {
    return Optional.of(abiClasspath.getArchiveMembers(getResolver()));
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
    Preconditions.checkState(useDependencyFileRuleKeys());
    return DefaultClassUsageFileReader.loadFromFile(
        getProjectFilesystem(),
        Preconditions.checkNotNull(depFileOutputPath),
        deps);
  }

}
