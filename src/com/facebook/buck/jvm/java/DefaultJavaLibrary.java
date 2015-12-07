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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasTests;
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
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
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

  @AddToRuleKey
  private final CompileStepFactory compileStepFactory;

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  private static final SuggestBuildRules.JarResolver JAR_RESOLVER =
      new SuggestBuildRules.JarResolver() {
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
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      ImmutableSet<Path> additionalClasspathEntries,
      CompileStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests) {
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
        Suppliers.memoize(
            new Supplier<ImmutableSortedSet<SourcePath>>() {
              @Override
              public ImmutableSortedSet<SourcePath> get() {
                return JavaLibraryRules.getAbiInputs(params.getDeps());
              }
            }),
        additionalClasspathEntries,
        compileStepFactory,
        resourcesRoot,
        mavenCoords,
        tests);
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
      final Supplier<ImmutableSortedSet<SourcePath>> abiClasspath,
      ImmutableSet<Path> additionalClasspathEntries,
      CompileStepFactory compileStepFactory,
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
    this.additionalClasspathEntries = additionalClasspathEntries;
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
    this.generatedSourceFolder = generatedSourceFolder;
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
      final BuildContext context,
      BuildableContext buildableContext) {
    final ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Only override the bootclasspath if this rule is supposed to compile Android code.
    ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getDeclaredClasspathEntries())
            .putAll(this, additionalClasspathEntries)
            .build();


    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    final BuildTarget target = getBuildTarget();
    final Path outputDirectory = getClassesDir(target);
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory));

    final SuggestBuildRules suggestBuildRule =
        DefaultSuggestBuildRules.createSuggestBuildFunction(
            JAR_RESOLVER, declaredClasspathEntries,
            ImmutableSetMultimap.<JavaLibrary, Path>builder()
                .putAll(getTransitiveClasspathEntries())
                .putAll(this, additionalClasspathEntries)
                .build(),
            context.getActionGraph().getNodes());

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

    final ImmutableSortedSet<Path> declared = ImmutableSortedSet.<Path>naturalOrder()
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
      final Path pathToSrcsList = BuildTargets.getGenPath(getBuildTarget(), "__%s__srcs");
      steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

      Path scratchDir = BuildTargets.getGenPath(target, "lib__%s____working_directory");
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      final Optional<Path> workingDirectory = Optional.of(scratchDir);

      compileStepFactory.installArtifacts(buildableContext);
      steps.add(new Step() {
        @Override
        public int execute(ExecutionContext eContext) throws IOException, InterruptedException {
          ImmutableSortedSet<Path> expandedSourcePaths =
              expandSourcesWithinZips(
                  getProjectFilesystem(),
                  target,
                  getJavaSrcs(),
                  workingDirectory);

          return compileStepFactory.compile(
              context,
              expandedSourcePaths,
              target,
              getResolver(),
              getProjectFilesystem(),
              declared,
              outputDirectory,
              workingDirectory,
              Optional.of(pathToSrcsList),
              Optional.of(suggestBuildRule),
              eContext
          );
        }

        @Override
        public String getShortName() {
          return "monster";
        }

        @Override
        public String getDescription(ExecutionContext context) {
          return "mmmmonster";
        }

        private ImmutableSortedSet<Path> expandSourcesWithinZips(
            ProjectFilesystem projectFilesystem,
            BuildTarget invokingRule,
            ImmutableSet<Path> javaSourceFilePaths,
            Optional<Path> workingDirectory) throws IOException {

          // Add sources file or sources list to command
          ImmutableSortedSet.Builder<Path> sources = ImmutableSortedSet.naturalOrder();
          for (Path path : javaSourceFilePaths) {
            String pathString = path.toString();
            if (pathString.endsWith(".java")) {
              sources.add(path);
            } else if (pathString.endsWith(JavaLibrary.SOURCE_ZIP) || pathString.endsWith(JavaLibrary.SOURCE_JAR)) {
              if (!workingDirectory.isPresent()) {
                throw new HumanReadableException(
                    "Attempting to compile target %s which specified a .src.zip input %s but no " +
                        "working directory was specified.",
                    invokingRule.toString(),
                    path);
              }
              // For a Zip of .java files, create a JavaFileObject for each .java entry.
              ImmutableList<Path> zipPaths = Unzip.extractZipFile(
                  projectFilesystem.resolve(path),
                  projectFilesystem.resolve(workingDirectory.get()),
                  Unzip.ExistingFileMode.OVERWRITE);
              sources.addAll(
                  FluentIterable.from(zipPaths)
                      .filter(
                          new Predicate<Path>() {
                            @Override
                            public boolean apply(Path input) {
                              return input.toString().endsWith(".java");
                            }
                          }));
            }
          }
          return sources.build();
        }
      });

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
