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

package com.facebook.buck.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.graph.TraversableGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

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
    implements JavaLibrary, AbiRule, HasClasspathEntries, ExportDependencies,
    InitializableFromDisk<JavaLibrary.Data>, AndroidPackageable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> resources;
  private final Optional<Path> outputJar;
  private final Optional<Path> proguardConfig;
  private final ImmutableList<String> postprocessClassesCommands;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final ImmutableSortedSet<BuildRule> providedDeps;
  // Some classes need to override this when enhancing deps (see AndroidLibrary).
  private final ImmutableSet<Path> additionalClasspathEntries;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      declaredClasspathEntriesSupplier;
  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final Optional<Path> resourcesRoot;

  // TODO(jacko): This really should be final, but we need to refactor how we get the
  // AndroidPlatformTarget first before it can be.
  private JavacOptions javacOptions;

  /**
   * Function for opening a JAR and returning all symbols that can be referenced from inside of that
   * jar.
   */
  @VisibleForTesting
  static interface JarResolver {
    public ImmutableSet<String> resolve(ProjectFilesystem filesystem, Path relativeClassPath);
  }

  private static final JarResolver JAR_RESOLVER =
      new JarResolver() {
    @Override
    public ImmutableSet<String> resolve(ProjectFilesystem filesystem, Path relativeClassPath) {
      ImmutableSet.Builder<String> topLevelSymbolsBuilder = ImmutableSet.builder();
      try {
        Path classPath = filesystem.getFileForRelativePath(relativeClassPath).toPath();
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
      BuildRuleParams params,
      SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      ImmutableSet<Path> additionalClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> resourcesRoot) {
    super(params, resolver);

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

    this.declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getDeclaredClasspathEntries(
                DefaultJavaLibrary.this);
          }
        });

    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  /**
   * @param outputDirectory Directory to write class files to
   * @param transitiveClasspathEntries Classpaths of all transitive dependencies.
   * @param declaredClasspathEntries Classpaths of all declared dependencies.
   * @param javacOptions javac configuration.
   * @param suggestBuildRules Function to convert from missing symbols to the suggested rules.
   * @param commands List of steps to add to.
   */
  @VisibleForTesting
  void createCommandsForJavac(
      Path outputDirectory,
      ImmutableSet<Path> transitiveClasspathEntries,
      ImmutableSet<Path> declaredClasspathEntries,
      JavacOptions javacOptions,
      BuildDependencies buildDependencies,
      Optional<JavacStep.SuggestBuildRules> suggestBuildRules,
      ImmutableList.Builder<Step> commands,
      BuildTarget target) {
    // Make sure that this directory exists because ABI information will be written here.
    Step mkdir = new MakeCleanDirectoryStep(getPathToAbiOutputDir());
    commands.add(mkdir);

    // Only run javac if there are .java files to compile.
    if (!getJavaSrcs().isEmpty()) {
      Path pathToSrcsList = BuildTargets.getGenPath(getBuildTarget(), "__%s__srcs");
      commands.add(new MkdirStep(pathToSrcsList.getParent()));

      Optional<Path> workingDirectory;
      if (getJavac().isUsingWorkspace()) {
        Path scratchDir = BuildTargets.getGenPath(target, "lib__%s____working_directory");
        commands.add(new MakeCleanDirectoryStep(scratchDir));
        workingDirectory = Optional.of(scratchDir);
      } else {
        workingDirectory = Optional.absent();
      }

      JavacStep javacStep = new JavacStep(
          outputDirectory,
          workingDirectory,
          getJavaSrcs(),
          Optional.of(pathToSrcsList),
          transitiveClasspathEntries,
          declaredClasspathEntries,
          javacOptions,
          target,
          buildDependencies,
          suggestBuildRules);

      commands.add(javacStep);
    }
  }

  /**
   * Creates the total ABI key for this rule. If export_deps is true, the total key is computed by
   * hashing the ABI keys of the dependencies together with the ABI key of this rule. If export_deps
   * is false, the standalone ABI key for this rule is used as the total key.
   * @param abiKey the standalone ABI key for this rule.
   * @return total ABI key containing also the ABI keys of the dependencies.
   */
  protected Sha1HashCode createTotalAbiKey(Sha1HashCode abiKey) {
    if (getExportedDeps().isEmpty()) {
      return abiKey;
    }

    SortedSet<HasBuildTarget> depsForAbiKey = getDepsForAbiKey();

    // Hash the ABI keys of all dependencies together with ABI key for the current rule.
    Hasher hasher = createHasherWithAbiKeyForDeps(depsForAbiKey);
    hasher.putUnencodedChars(abiKey.getHash());
    return ImmutableSha1HashCode.of(hasher.hash().toString());
  }

  private Path getPathToAbiOutputDir() {
    return BuildTargets.getGenPath(getBuildTarget(), "lib__%s__abi");
  }

  private static Path getOutputJarDirPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "lib__%s__output");
  }

  private static Path getOutputJarPath(BuildTarget target) {
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

  /**
   * Finds all deps that implement JavaLibraryRule and hash their ABI keys together.
   */
  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return ImmutableSha1HashCode.of(
        createHasherWithAbiKeyForDeps(getDepsForAbiKey()).hash().toString());
  }

  /**
   * Returns a sorted set containing the dependencies which will be hashed in the final ABI key.
   * @return the dependencies to be hashed in the final ABI key.
   */
  private SortedSet<HasBuildTarget> getDepsForAbiKey() {
    SortedSet<HasBuildTarget> rulesWithAbiToConsider = Sets.newTreeSet(BUILD_TARGET_COMPARATOR);
    for (BuildRule dep : Iterables.concat(getDepsForTransitiveClasspathEntries(), providedDeps)) {
      // This looks odd. DummyJavaAbiRule contains a Buildable that isn't a JavaAbiRule.
      if (dep instanceof HasJavaAbi) {
        if (dep instanceof JavaLibrary) {
          JavaLibrary javaRule = (JavaLibrary) dep;
          rulesWithAbiToConsider.addAll(javaRule.getOutputClasspathEntries().keys());
        } else {
          rulesWithAbiToConsider.add(dep);
        }
      }
    }

    // We also need to iterate over inputs that are SourcePaths, since they're only listed as
    // compile-time deps and not in the "deps" field. If any of these change, we should recompile
    // the library, since we will (at least) need to repack it.
    rulesWithAbiToConsider.addAll(
        getResolver().filterBuildRuleInputs(Iterables.concat(srcs, resources)));

    return rulesWithAbiToConsider;
  }

  /**
   * Creates a Hasher containing the ABI keys of the dependencies.
   * @param rulesWithAbiToConsider a sorted set containing the dependencies whose ABI key will be
   *     added to the hasher.
   * @return a Hasher containing the ABI keys of the dependencies.
   */
  private Hasher createHasherWithAbiKeyForDeps(SortedSet<HasBuildTarget> rulesWithAbiToConsider) {
    Hasher hasher = Hashing.sha1().newHasher();

    for (HasBuildTarget candidate : rulesWithAbiToConsider) {
      if (candidate == this) {
        continue;
      }

      if (candidate instanceof HasJavaAbi) {
        Sha1HashCode abiKey = ((HasJavaAbi) candidate).getAbiKey();
        hasher.putUnencodedChars(abiKey.getHash());
      } else if (candidate instanceof BuildRule) {
        HashCode hashCode = ((BuildRule) candidate).getRuleKey().getHashCode();
        hasher.putBytes(hashCode.asBytes());
      }
    }

    return hasher;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setReflectively("postprocessClassesCommands", postprocessClassesCommands)
        .setReflectively("resources", resources)
        .setReflectively("resources_root", resourcesRoot.toString())
        // provided_deps are already included in the rule key, but we need to explicitly call them
        // out as "provided" because changing a dep from provided to transtitive should result in a
        // re-build (otherwise, we'd get a rule key match).
        .setReflectively("provided_deps", providedDeps);
    return javacOptions.appendToRuleKey(builder, "javacOptions");
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().getAllPaths(srcs));
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
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return javacOptions.getAnnotationProcessingParams();
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    builder.addAll(getResolver().filterInputsToCompareToOutput(this.srcs));
    builder.addAll(getResolver().filterInputsToCompareToOutput(this.resources));
    Optionals.addIfPresent(this.proguardConfig, builder);
    return builder.build();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  public JavacOptions getJavacOptions() {
    return javacOptions;
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

    ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getTransitiveClasspathEntries())
            .putAll(this, additionalClasspathEntries)
            .build();

    ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getDeclaredClasspathEntries())
            .putAll(this, additionalClasspathEntries)
            .build();

    // Javac requires that the root directory for generated sources already exist.
    Path annotationGenFolder =
        javacOptions.getAnnotationProcessingParams().getGeneratedSourceFolderName();
    if (annotationGenFolder != null) {
      MakeCleanDirectoryStep mkdirGeneratedSources =
          new MakeCleanDirectoryStep(annotationGenFolder);
      steps.add(mkdirGeneratedSources);
      buildableContext.recordArtifactsInDirectory(annotationGenFolder);
    }

    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    BuildTarget target = getBuildTarget();
    Path outputDirectory = getClassesDir(target);
    steps.add(new MakeCleanDirectoryStep(outputDirectory));

    Optional<JavacStep.SuggestBuildRules> suggestBuildRule =
        createSuggestBuildFunction(context,
            transitiveClasspathEntries,
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

    ImmutableSet<Path> transitive = ImmutableSet.<Path>builder()
        .addAll(transitiveClasspathEntries.values())
        .addAll(provided)
        .build();

    ImmutableSet<Path> declared = ImmutableSet.<Path>builder()
        .addAll(declaredClasspathEntries.values())
        .addAll(provided)
        .build();

    // This adds the javac command, along with any supporting commands.
    createCommandsForJavac(
        outputDirectory,
        transitive,
        declared,
        javacOptions,
        context.getBuildDependencies(),
        suggestBuildRule,
        steps,
        target);

    addPostprocessClassesCommands(steps, postprocessClassesCommands, outputDirectory);

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }
    steps.add(
        new CopyResourcesStep(
            getResolver(),
            target,
            resources,
            outputDirectory,
            finder));

    steps.add(new MakeCleanDirectoryStep(getOutputJarDirPath(target)));

    Path abiJar = getOutputJarDirPath(target)
        .resolve(String.format("%s-abi.jar", target.getShortNameAndFlavorPostfix()));
    steps.add(new MkdirStep(abiJar.getParent()));

    if (outputJar.isPresent()) {
      Path output = outputJar.get();

      steps.add(new JarDirectoryStep(
              output,
          Collections.singleton(outputDirectory),
          /* mainClass */ null,
          /* manifestFile */ null));
      buildableContext.recordArtifact(output);

      // Calculate the ABI.

      steps.add(new CalculateAbiStep(buildableContext, output, abiJar));
    } else {
      Path scratch = BuildTargets.getScratchPath(
          target,
          String.format("%%s/%s-temp-abi.jar", target.getShortNameAndFlavorPostfix()));
      steps.add(new MakeCleanDirectoryStep(scratch.getParent()));
      steps.add(new TouchStep(scratch));
      steps.add(new CalculateAbiStep(buildableContext, scratch, abiJar));
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
      BuildContext context,
      ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries,
      ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries,
      final JarResolver jarResolver) {
    if (context.getBuildDependencies() != BuildDependencies.WARN_ON_TRANSITIVE) {
      return Optional.absent();
    }
    final Set<JavaLibrary> transitiveNotDeclaredDeps = Sets.difference(
        transitiveClasspathEntries.keySet(),
        Sets.union(ImmutableSet.of(this), declaredClasspathEntries.keySet()));

    TraversableGraph<BuildRule> graph = context.getActionGraph();
    final ImmutableList<JavaLibrary> sortedTransitiveNotDeclaredDeps = FluentIterable
        .from(TopologicalSort.sort(graph, Predicates.<BuildRule>alwaysTrue()))
        .filter(JavaLibrary.class)
        .filter(Predicates.in(transitiveNotDeclaredDeps))
        .toList()
        .reverse();

    JavacStep.SuggestBuildRules suggestBuildRuleFn =
        new JavacStep.SuggestBuildRules() {
      @Override
      public ImmutableSet<String> suggest(ProjectFilesystem filesystem,
          ImmutableSet<String> failedImports) {
        ImmutableSet.Builder<String> suggestedDeps = ImmutableSet.builder();

        Set<String> remainingImports = Sets.newHashSet(failedImports);

        for (JavaLibrary transitiveNotDeclaredDep : sortedTransitiveNotDeclaredDeps) {
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
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return buildOutputInitializer.getBuildOutput().getAbiKey();
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
   * @param commands the list of Steps we are building.
   * @param postprocessClassesCommands the list of commands to post-process .class files.
   * @param outputDirectory the directory that will contain all the javac output.
   */
  @VisibleForTesting
  static void addPostprocessClassesCommands(
      ImmutableList.Builder<Step> commands,
      List<String> postprocessClassesCommands,
      Path outputDirectory) {
    for (final String postprocessClassesCommand : postprocessClassesCommands) {
      BashStep bashStep = new BashStep(postprocessClassesCommand + " " + outputDirectory);
      commands.add(bashStep);
    }
  }

  @VisibleForTesting
  public Javac getJavac() {
    return javacOptions.getJavac();
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
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
  public void addToCollector(AndroidPackageableCollector collector) {
    if (outputJar.isPresent()) {
      collector.addClasspathEntry(this, outputJar.get());
    }
    if (proguardConfig.isPresent()) {
      collector.addProguardConfig(getBuildTarget(), proguardConfig.get());
    }
  }

}
