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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static com.google.common.base.Optional.fromNullable;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
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
    SupportsInputBasedRuleKey, HasTests {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;
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
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      declaredClasspathEntriesSupplier;

  private final SourcePath abiJar;
  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final Supplier<ImmutableSortedSet<SourcePath>> abiClasspath;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final Optional<Path> generatedSourceFolder;

  // TODO(jacko): This really should be final, but we need to refactor how we get the
  // AndroidPlatformTarget first before it can be.
  @AddToRuleKey
  private JavacOptions javacOptions;

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  /**
   * Function for opening a JAR and returning all symbols that can be referenced from inside of that
   * jar.
   */
  @VisibleForTesting
  interface JarResolver {
    ImmutableSet<String> resolve(ProjectFilesystem filesystem, Path relativeClassPath);
  }

  private static final JarResolver JAR_RESOLVER =
      new JarResolver() {
    @Override
    public ImmutableSet<String> resolve(ProjectFilesystem filesystem, Path relativeClassPath) {
      ImmutableSet.Builder<String> topLevelSymbolsBuilder = ImmutableSet.builder();
      try {
        Path classPath = filesystem.getPathForRelativePath(relativeClassPath);
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
    }
  };

  public DefaultJavaLibrary(
      final BuildRuleParams params,
      SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      ImmutableSet<Path> additionalClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> resourcesRoot,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests) {
    this(
        params,
        resolver,
        srcs,
        resources,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        abiJar,
        Suppliers.memoize(
            new Supplier<ImmutableSortedSet<SourcePath>>() {
              @Override
              public ImmutableSortedSet<SourcePath> get() {
                return JavaLibraryRules.getAbiInputs(params.getDeps());
              }
            }),
        additionalClasspathEntries,
        javacOptions,
        resourcesRoot,
        mavenCoords,
        tests);
  }

  private DefaultJavaLibrary(
      BuildRuleParams params,
      final SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      final Supplier<ImmutableSortedSet<SourcePath>> abiClasspath,
      ImmutableSet<Path> additionalClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> resourcesRoot,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests) {
    super(
        params.appendExtraDeps(
            new Supplier<Iterable<? extends BuildRule>>() {
              @Override
              public Iterable<? extends BuildRule> get() {
                return resolver.filterBuildRuleInputs(abiClasspath.get());
              }
            }),
        resolver);

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
    this.additionalClasspathEntries = additionalClasspathEntries;
    this.javacOptions = javacOptions;
    this.resourcesRoot = resourcesRoot;
    this.mavenCoords = mavenCoords;
    this.tests = tests;

    this.abiJar = abiJar;
    this.abiClasspath = abiClasspath;

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget()));
    } else {
      this.outputJar = Optional.absent();
    }

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getOutputClasspathEntries(
                DefaultJavaLibrary.this,
                outputJar);
          }
        });

    this.transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getTransitiveClasspathEntries(
                DefaultJavaLibrary.this,
                outputJar);
          }
        });

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSet<JavaLibrary>>() {
              @Override
              public ImmutableSet<JavaLibrary> get() {
                return JavaLibraryClasspathProvider.getTransitiveClasspathDeps(
                    DefaultJavaLibrary.this,
                    outputJar);
              }
            });

    this.declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getDeclaredClasspathEntries(
                DefaultJavaLibrary.this);
          }
        });

    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    this.generatedSourceFolder = fromNullable(
        javacOptions.getAnnotationProcessingParams().getGeneratedSourceFolderName());
  }

  private Path getPathToAbiOutputDir() {
    return BuildTargets.getGenPath(getBuildTarget(), "lib__%s__abi");
  }

  private static Path getOutputJarDirPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "lib__%s__output");
  }

  static Path getOutputJarPath(BuildTarget target) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target),
            target.getShortNameAndFlavorPostfix()));
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   *     The return value does not end with a slash.
   */
  private static Path getClassesDir(BuildTarget target) {
    return BuildTargets.getScratchPath(target, "lib__%s__classes");
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
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
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
   * attribute. They are compiled into a directory under
   * {@link com.facebook.buck.util.BuckConstant#SCRATCH_DIR}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Only override the bootclasspath if this rule is supposed to compile Android code.
    if (getProperties().is(ANDROID)) {
      this.javacOptions = JavacOptions.builder(javacOptions)
          .setBootclasspath(context.getAndroidBootclasspathSupplier().get())
          .build();
    }

    ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getDeclaredClasspathEntries())
            .putAll(this, additionalClasspathEntries)
            .build();

    JavacStepFactory javacStepFactory = new JavacStepFactory(javacOptions);

    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    BuildTarget target = getBuildTarget();
    Path outputDirectory = getClassesDir(target);
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory));

    Optional<JavacStep.SuggestBuildRules> suggestBuildRule =
        createSuggestBuildFunction(
            context,
            declaredClasspathEntries,
            JAR_RESOLVER);

    // We don't want to add these to the declared or transitive deps, since they're only used at
    // compile time.
    Collection<Path> provided = JavaLibraryClasspathProvider.getJavaLibraryDeps(providedDeps)
        .transformAndConcat(
            new Function<JavaLibrary, Collection<Path>>() {
              @Override
              public Collection<Path> apply(JavaLibrary input) {
                return input.getOutputClasspathEntries().values();
              }
            })
        .filter(Predicates.notNull())
        .toSet();

    ImmutableSortedSet<Path> declared = ImmutableSortedSet.<Path>naturalOrder()
        .addAll(declaredClasspathEntries.values())
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

    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), getOutputJarDirPath(target)));

    // Only run javac if there are .java files to compile.
    if (!getJavaSrcs().isEmpty()) {
      Path output = outputJar.get();
      // This adds the javac command, along with any supporting commands.
      Path pathToSrcsList = BuildTargets.getGenPath(getBuildTarget(), "__%s__srcs");
      steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

      Path scratchDir = BuildTargets.getGenPath(target, "lib__%s____working_directory");
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      Optional<Path> workingDirectory = Optional.of(scratchDir);

      javacStepFactory.createCompileStep(
          getJavaSrcs(),
          target,
          getResolver(),
          getProjectFilesystem(),
          declared,
          outputDirectory,
          workingDirectory,
          Optional.of(pathToSrcsList),
          suggestBuildRule,
          steps,
          buildableContext);

      steps.addAll(Lists.newCopyOnWriteArrayList(addPostprocessClassesCommands(
              getProjectFilesystem().getRootPath(),
              postprocessClassesCommands,
              outputDirectory)));
      steps.add(
          new JarDirectoryStep(
              getProjectFilesystem(),
              output,
              ImmutableSortedSet.of(outputDirectory),
              /* mainClass */null,
              /* manifestFile */null));
    }


    Path abiJar = getOutputJarDirPath(target)
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
          /* manifestFile */ null));
      }
      buildableContext.recordArtifact(output);

      // Calculate the ABI.

      steps.add(new CalculateAbiStep(buildableContext, getProjectFilesystem(), output, abiJar));
    } else {
      Path scratch = BuildTargets.getScratchPath(
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
   *  @param transitiveNotDeclaredRule A {@link BuildRule} that is contained in the transitive
   *      dependency list but is not declared as a dependency.
   *  @param failedImports A Set of remaining failed imports.  This function will mutate this set
   *      and remove any imports satisfied by {@code transitiveNotDeclaredDep}.
   *  @return whether or not adding {@code transitiveNotDeclaredDep} as a dependency to this build
   *      rule would have satisfied one of the {@code failedImports}.
   */
  private boolean isMissingBuildRule(ProjectFilesystem filesystem,
      BuildRule transitiveNotDeclaredRule,
      Set<String> failedImports,
      JarResolver jarResolver) {
    if (!(transitiveNotDeclaredRule instanceof JavaLibrary)) {
      return false;
    }

    ImmutableSet<Path> classPaths =
        ImmutableSet.copyOf(
            ((JavaLibrary) transitiveNotDeclaredRule).getOutputClasspathEntries().values());
    boolean containsMissingBuildRule = false;
    // Open the output jar for every jar contained as the output of transitiveNotDeclaredDep.  With
    // the exception of rules that export their dependencies, this will result in a single
    // classpath.
    for (Path classPath : classPaths) {
      ImmutableSet<String> topLevelSymbols = jarResolver.resolve(filesystem, classPath);

      for (String symbolName : topLevelSymbols) {
        if (failedImports.contains(symbolName)) {
          failedImports.remove(symbolName);
          containsMissingBuildRule = true;

          // If we've found all of the missing imports, bail out early.
          if (failedImports.isEmpty()) {
            return true;
          }
        }
      }
    }
    return containsMissingBuildRule;
  }

  /**
   * @return A function that takes a list of failed imports from a javac invocation and returns a
   *    set of rules to suggest that the developer import to satisfy those imports.
   */
  @VisibleForTesting
  Optional<JavacStep.SuggestBuildRules> createSuggestBuildFunction(
      final BuildContext context,
      final ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries,
      final JarResolver jarResolver) {

    final Supplier<ImmutableList<JavaLibrary>> sortedTransitiveNotDeclaredDeps =
        Suppliers.memoize(
            new Supplier<ImmutableList<JavaLibrary>>() {
              @Override
              public ImmutableList<JavaLibrary> get() {
                ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries =
                    ImmutableSetMultimap.<JavaLibrary, Path>builder()
                        .putAll(getTransitiveClasspathEntries())
                        .putAll(
                            DefaultJavaLibrary.this,
                            DefaultJavaLibrary.this.additionalClasspathEntries)
                        .build();
                Set<JavaLibrary> transitiveNotDeclaredDeps = Sets.difference(
                    transitiveClasspathEntries.keySet(),
                    Sets.union(ImmutableSet.of(this), declaredClasspathEntries.keySet()));
                DirectedAcyclicGraph<BuildRule> graph =
                    BuildRuleDependencyVisitors.getBuildRuleDirectedGraphFilteredBy(
                        context.getActionGraph().getNodes(),
                        Predicates.instanceOf(JavaLibrary.class),
                        Predicates.instanceOf(JavaLibrary.class));
                return FluentIterable
                    .from(TopologicalSort.sort(graph, Predicates.<BuildRule>alwaysTrue()))
                    .filter(JavaLibrary.class)
                    .filter(Predicates.in(transitiveNotDeclaredDeps))
                    .toList()
                    .reverse();
              }
            });

    JavacStep.SuggestBuildRules suggestBuildRuleFn =
        new JavacStep.SuggestBuildRules() {
      @Override
      public ImmutableSet<String> suggest(ProjectFilesystem filesystem,
          ImmutableSet<String> failedImports) {
        ImmutableSet.Builder<String> suggestedDeps = ImmutableSet.builder();

        Set<String> remainingImports = Sets.newHashSet(failedImports);

        for (JavaLibrary transitiveNotDeclaredDep : sortedTransitiveNotDeclaredDeps.get()) {
          if (isMissingBuildRule(filesystem,
                  transitiveNotDeclaredDep,
                  remainingImports,
                  jarResolver)) {
            suggestedDeps.add(transitiveNotDeclaredDep.getFullyQualifiedName());
          }
          // If we've wiped out all remaining imports, break the loop looking for them.
          if (remainingImports.isEmpty()) {
            break;
          }
        }
        return suggestedDeps.build();
      }
    };
    return Optional.of(suggestBuildRuleFn);
  }

  /**
   * Instructs this rule to report the ABI it has on disk as its current ABI.
   */
  @Override
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return outputJar.isPresent() ? Optional.of(abiJar) : Optional.<SourcePath>absent();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  /**
   * Adds a BashStep for each postprocessClasses command that runs the command followed by the
   * outputDirectory of javac outputs.
   *
   * The expectation is that the command will inspect and update the directory by
   * modifying, adding, and deleting the .class files in the directory.
   *
   * The outputDirectory should be a valid java root.  I.e., if outputDirectory
   * is buck-out/bin/java/abc/lib__abc__classes/, then a contained class abc.AbcModule
   * should be at buck-out/bin/java/abc/lib__abc__classes/abc/AbcModule.class
   *
   * @param postprocessClassesCommands the list of commands to post-process .class files.
   * @param outputDirectory the directory that will contain all the javac output.
   */
  @VisibleForTesting
  static ImmutableList<Step> addPostprocessClassesCommands(
      Path workingDirectory,
      List<String> postprocessClassesCommands,
      Path outputDirectory) {
    ImmutableList.Builder<Step> commands = new ImmutableList.Builder<Step>();
    for (final String postprocessClassesCommand : postprocessClassesCommands) {
      BashStep bashStep = new BashStep(
          workingDirectory,
          postprocessClassesCommand + " " + outputDirectory);
      commands.add(bashStep);
    }
    return commands.build();
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

}
