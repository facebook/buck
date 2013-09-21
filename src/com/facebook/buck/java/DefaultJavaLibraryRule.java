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

import com.facebook.buck.android.HasAndroidResourceDeps;
import com.facebook.buck.android.UberRDotJavaUtil;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.ResourcesAttributeBuilder;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Paths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.reflect.ClassPath;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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
public class DefaultJavaLibraryRule extends DoNotUseAbstractBuildable
    implements JavaLibraryRule, AbiRule, HasJavaSrcs, HasClasspathEntries {

  private final static BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final ImmutableSortedSet<String> srcs;

  private final ImmutableSortedSet<SourcePath> resources;

  private final Optional<String> outputJar;

  private final List<String> inputsToConsiderForCachingPurposes;

  private final Optional<String> proguardConfig;


  private final boolean exportDeps;

  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>> outputClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>
      declaredClasspathEntriesSupplier;

  private final JavacOptions javacOptions;

  /**
   * This returns the ABI key for this rule. This will be set <em>EITHER</em> as part of
   * {@link #initializeFromDisk(OnDiskBuildInfo)}, or while the build steps (in particular, the
   * javac step) for this rule are created. In the case of the latter, the {@link Supplier} is
   * guaranteed to be able to return (a possibly null) value after the build steps have been
   * executed.
   * <p>
   * This field should be set exclusively through {@link #setAbiKey(Supplier)}
   */
  @Nullable
  private Supplier<Sha1HashCode> abiKeySupplier;

  /**
   * Function for opening a JAR and returning all symbols that can be referenced from inside of that
   * jar.
   */
  @VisibleForTesting
  static interface JarResolver extends Function<String, ImmutableSet<String>> {}

  private final JarResolver JAR_RESOLVER =
      new JarResolver() {
    @Override
    public ImmutableSet<String> apply(String classPath) {
      ImmutableSet.Builder<String> topLevelSymbolsBuilder = ImmutableSet.builder();
      try {
        ClassLoader loader = URLClassLoader.newInstance(
            new URL[]{new File(classPath).toURI().toURL()},
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

  /**
   * This is set in
   * {@link com.facebook.buck.rules.Buildable#getBuildSteps(com.facebook.buck.rules.BuildContext, BuildableContext)}
   * and is available to subclasses.
   */
  protected ImmutableList<HasAndroidResourceDeps> androidResourceDeps;

  protected DefaultJavaLibraryRule(BuildRuleParams buildRuleParams,
                                   Set<String> srcs,
                                   Set<? extends SourcePath> resources,
                                   Optional<String> proguardConfig,
                                   boolean exportDeps,
                                   JavacOptions javacOptions) {
    super(buildRuleParams);
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
    this.exportDeps = exportDeps;
    this.javacOptions = Preconditions.checkNotNull(javacOptions);

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget()));
    } else {
      this.outputJar = Optional.absent();
    }

    // Note that both srcs and resources are sorted so that the list order is consistent even if
    // the iteration order of the sets passed to the constructor changes. See
    // AbstractBuildRule.getInputsToCompareToOutput() for details.
    ImmutableList.Builder<String> builder = ImmutableList.<String>builder().addAll(this.srcs);
    builder.addAll(SourcePaths.filterInputsToCompareToOutput(resources));
    inputsToConsiderForCachingPurposes = builder.build();

    outputClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>() {
          @Override
          public ImmutableSetMultimap<JavaLibraryRule, String> get() {
            ImmutableSetMultimap<JavaLibraryRule, String> outputClasspathEntries;

            // If this java_library exports its dependencies then just return the transitive
            // dependencies.
            if (DefaultJavaLibraryRule.this.exportDeps) {
              outputClasspathEntries = getTransitiveClasspathEntries();
            } else if (outputJar.isPresent()) {
              outputClasspathEntries = ImmutableSetMultimap.<JavaLibraryRule, String>builder()
                  .put(DefaultJavaLibraryRule.this, getPathToOutputFile())
                  .build();
            } else {
              outputClasspathEntries = ImmutableSetMultimap.of();
            }

            return outputClasspathEntries;
          }
        });

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>() {
          @Override
          public ImmutableSetMultimap<JavaLibraryRule, String> get() {
            final ImmutableSetMultimap.Builder<JavaLibraryRule, String> classpathEntries =
                ImmutableSetMultimap.builder();
            ImmutableSetMultimap<JavaLibraryRule, String> classpathEntriesForDeps =
                Classpaths.getClasspathEntries(getDeps());

            classpathEntries.putAll(classpathEntriesForDeps);

            if (DefaultJavaLibraryRule.this.exportDeps) {
              classpathEntries.putAll(DefaultJavaLibraryRule.this,
                  classpathEntriesForDeps.values());
            }

            // Only add ourselves to the classpath if there's a jar to be built.
            if (outputJar.isPresent()) {
              classpathEntries.putAll(DefaultJavaLibraryRule.this, getPathToOutputFile());
            }

            return classpathEntries.build();
          }
        });

    declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibraryRule, String>>() {
          @Override
          public ImmutableSetMultimap<JavaLibraryRule, String> get() {
            final ImmutableSetMultimap.Builder<JavaLibraryRule, String> classpathEntries =
               ImmutableSetMultimap.builder();

            Iterable<JavaLibraryRule> javaLibraryDeps = Iterables.filter(
                Sets.union(getDeps(), ImmutableSet.of(DefaultJavaLibraryRule.this)),
                JavaLibraryRule.class);

            for (JavaLibraryRule rule : javaLibraryDeps) {
              classpathEntries.putAll(rule, rule.getOutputClasspathEntries().values());
            }
            return classpathEntries.build();
          }
        });
  }

  /**
   * @param outputDirectory Directory to write class files to
   * @param transitiveClasspathEntries Classpaths of all transitive dependencies.
   * @param declaredClasspathEntries Classpaths of all declared dependencies.
   * @param javacOptions options to use when compiling code.
   * @param suggestBuildRules Function to convert from missing symbols to the suggested rules.
   * @param commands List of steps to add to.
   */
  private void createCommandsForJavac(
      String outputDirectory,
      ImmutableSet<String> transitiveClasspathEntries,
      ImmutableSet<String> declaredClasspathEntries,
      JavacOptions javacOptions,
      BuildDependencies buildDependencies,
      Optional<DependencyCheckingJavacStep.SuggestBuildRules> suggestBuildRules,
      ImmutableList.Builder<Step> commands) {
    // Make sure that this directory exists because ABI information will be written here.
    Step mkdir = new MakeCleanDirectoryStep(getPathToAbiOutputDir());
    commands.add(mkdir);

    // Only run javac if there are .java files to compile.
    if (!getJavaSrcs().isEmpty()) {
      final JavacInMemoryStep javac = new DependencyCheckingJavacStep(
          outputDirectory,
          getJavaSrcs(),
          transitiveClasspathEntries,
          declaredClasspathEntries,
          javacOptions,
          Optional.of(getPathToAbiOutputFile()),
          Optional.of(getFullyQualifiedName()),
          buildDependencies,
          suggestBuildRules);
      commands.add(javac);

      // Create a supplier that extracts the ABI key from javac after it executes.
      setAbiKey(Suppliers.memoize(new Supplier<Sha1HashCode>() {
        @Override
        public Sha1HashCode get() {
          return javac.getAbiKey();
        }
      }));
    } else {
      // When there are no .java files to compile, the ABI key should be a constant.
      setAbiKey(Suppliers.ofInstance(new Sha1HashCode(AbiWriterProtocol.EMPTY_ABI_KEY)));
    }
  }

  private String getPathToAbiOutputDir() {
    BuildTarget target = getBuildTarget();
    return String.format(
        "%s/%slib__%s__abi",
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName());
  }

  private String getPathToAbiOutputFile() {
    return String.format("%s/abi", getPathToAbiOutputDir());
  }

  private static String getOutputJarDirPath(BuildTarget target) {
    return String.format(
        "%s/%slib__%s__output",
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName());
  }

  private static String getOutputJarPath(BuildTarget target) {
    return String.format(
        "%s/%s.jar",
        getOutputJarDirPath(target),
        target.getShortName());
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   *     The return value does not end with a slash.
   */
  private static String getClassesDir(BuildTarget target) {
    return String.format(
        "%s/%slib__%s__classes",
        BuckConstant.BIN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName());
  }

  /**
   * Finds all deps that implement JavaLibraryRule and hash their ABI keys together. If any dep
   * lacks an ABI key, then returns {@link Optional#absent()}.
   */
  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    SortedSet<JavaLibraryRule> rulesWithAbiToConsider = Sets.newTreeSet();
    for (BuildRule dep : getDeps()) {
      if (dep instanceof JavaLibraryRule) {
        JavaLibraryRule javaRule = (JavaLibraryRule)dep;
        rulesWithAbiToConsider.addAll(javaRule.getOutputClasspathEntries().keys());
      }
    }

    Hasher hasher = Hashing.sha1().newHasher();
    for (JavaLibraryRule ruleWithAbiToConsider : rulesWithAbiToConsider) {
      if (ruleWithAbiToConsider == this) {
        continue;
      }

      Sha1HashCode abiKey = ruleWithAbiToConsider.getAbiKey();
      hasher.putUnencodedChars(abiKey.getHash());
    }

    return new Sha1HashCode(hasher.hash().toString());
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    super.appendToRuleKey(builder)
        .set("srcs", srcs)
        .setSourcePaths("resources", resources)
        .set("proguard", proguardConfig)
        .set("exportDeps", exportDeps);
    javacOptions.appendToRuleKey(builder);
    return builder;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.JAVA_LIBRARY;
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSortedSet<String> getJavaSrcs() {
    return srcs;
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getOutputClasspathEntries() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return javacOptions.getAnnotationProcessingData();
  }

  public Optional<String> getProguardConfig() {
    return proguardConfig;
  }

  @Override
  @Nullable
  public List<String> getInputsToCompareToOutput() {
    return inputsToConsiderForCachingPurposes;
  }

  @Override
  public boolean getExportDeps() {
    return exportDeps;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under {@link BuckConstant#BIN_DIR}.
   */
  @Override
  public final List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    BuildTarget buildTarget = getBuildTarget();

    JavacOptions javacOptions = this.javacOptions;
    // Only override the bootclasspath if this rule is supposed to compile Android code.
    if (getProperties().is(ANDROID)) {
      javacOptions = JavacOptions.builder(this.javacOptions)
          .setBootclasspath(context.getAndroidBootclasspathSupplier().get())
          .build();
    }

    // If this rule depends on AndroidResourceRules, then we need to generate the R.java files that
    // this rule needs in order to be able to compile itself.
    androidResourceDeps = UberRDotJavaUtil.getAndroidResourceDeps(this,
        context.getDependencyGraph());
    boolean dependsOnAndroidResourceRules = !androidResourceDeps.isEmpty();
    if (dependsOnAndroidResourceRules) {
      UberRDotJavaUtil.createDummyRDotJavaFiles(androidResourceDeps, buildTarget, commands);
    }

    ImmutableSetMultimap<JavaLibraryRule, String> transitiveClasspathEntries =
        getTransitiveClasspathEntries();
    ImmutableSetMultimap<JavaLibraryRule, String> declaredClasspathEntries =
        getDeclaredClasspathEntries();

    // If this rule depends on AndroidResourceRules, then we need to include the compiled R.java
    // files on the classpath when compiling this rule.
    if (dependsOnAndroidResourceRules) {
      ImmutableSetMultimap.Builder<JavaLibraryRule, String> transitiveClasspathEntriesWithRDotJava =
          ImmutableSetMultimap.builder();
      transitiveClasspathEntriesWithRDotJava.putAll(transitiveClasspathEntries);

      ImmutableSetMultimap.Builder<JavaLibraryRule, String> declaredClasspathEntriesWithRDotJava =
          ImmutableSetMultimap.builder();
      declaredClasspathEntriesWithRDotJava.putAll(declaredClasspathEntries);

      ImmutableSet<String> rDotJavaClasspath =
          ImmutableSet.of(UberRDotJavaUtil.getRDotJavaBinFolder(buildTarget));

      transitiveClasspathEntriesWithRDotJava.putAll(this, rDotJavaClasspath);
      declaredClasspathEntriesWithRDotJava.putAll(this, rDotJavaClasspath);

      declaredClasspathEntries = declaredClasspathEntriesWithRDotJava.build();
      transitiveClasspathEntries = transitiveClasspathEntriesWithRDotJava.build();
    }

    // Javac requires that the root directory for generated sources already exist.
    String annotationGenFolder =
        javacOptions.getAnnotationProcessingData().getGeneratedSourceFolderName();
    if (annotationGenFolder != null) {
      MakeCleanDirectoryStep mkdirGeneratedSources =
          new MakeCleanDirectoryStep(annotationGenFolder);
      commands.add(mkdirGeneratedSources);
    }

    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    String outputDirectory = getClassesDir(getBuildTarget());
    commands.add(new MakeCleanDirectoryStep(outputDirectory));

    Optional<DependencyCheckingJavacStep.SuggestBuildRules> suggestBuildRule =
        createSuggestBuildFunction(context,
            transitiveClasspathEntries,
            declaredClasspathEntries,
            JAR_RESOLVER);

    // This adds the javac command, along with any supporting commands.
    createCommandsForJavac(
        outputDirectory,
        ImmutableSet.copyOf(transitiveClasspathEntries.values()),
        ImmutableSet.copyOf(declaredClasspathEntries.values()),
        javacOptions,
        context.getBuildDependencies(),
        suggestBuildRule,
        commands);


    // If there are resources, then link them to the appropriate place in the classes directory.
    addResourceCommands(context, commands, outputDirectory, context.getJavaPackageFinder());

    if (outputJar.isPresent()) {
      commands.add(new MakeCleanDirectoryStep(getOutputJarDirPath(getBuildTarget())));
      commands.add(new JarDirectoryStep(
          outputJar.get(),
          Collections.singleton(outputDirectory),
          /* mainClass */ null,
          /* manifestFile */ null));
    }

    Preconditions.checkNotNull(abiKeySupplier,
        "abiKeySupplier must be set so that getAbiKey() will " +
        "return a non-null value if this rule builds successfully.");

    addStepsToRecordAbiToDisk(commands, buildableContext);

    return commands.build();
  }

  /**
   * Assuming the build has completed successfully, the ABI should have been computed, and it should
   * be stored for subsequent builds.
   */
  private void addStepsToRecordAbiToDisk(ImmutableList.Builder<Step> commands,
      final BuildableContext buildableContext) throws IOException {
    // Note that the parent directories for all of the files written by these steps should already
    // have been created by a previous step. Therefore, there is no reason to add a MkdirStep here.
    commands.add(new AbstractExecutionStep("recording ABI metadata") {
      @Override
      public int execute(ExecutionContext context) {
        Sha1HashCode abiKey = abiKeySupplier.get();
        buildableContext.addMetadata(ABI_KEY_ON_DISK_METADATA, abiKey.getHash());
        return 0;
      }
    });

    buildableContext.addMetadata(ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
        getAbiKeyForDeps().getHash());
  }

  /**
   *  @param transitiveNotDeclaredDep A {@link BuildRule} that is contained in the transitive
   *      dependency list but is not declared as a dependency.
   *  @param failedImports A Set of remaining failed imports.  This function will mutate this set
   *      and remove any imports satisfied by {@code transitiveNotDeclaredDep}.
   *  @return whether or not adding {@code transitiveNotDeclaredDep} as a dependency to this build
   *      rule would have satisfied one of the {@code failedImports}.
   */
  private boolean isMissingBuildRule(BuildRule transitiveNotDeclaredDep,
      Set<String> failedImports,
      JarResolver jarResolver) {
    if (!(transitiveNotDeclaredDep instanceof JavaLibraryRule)) {
      return false;
    }

    ImmutableSet<String> classPaths = getTransitiveClasspathEntries()
        .get((JavaLibraryRule)transitiveNotDeclaredDep);
    boolean containsMissingBuildRule = false;
    // Open the output jar for every jar contained as the output of transitiveNotDeclaredDep.  With
    // the exception of rules that export their dependencies, this will result in a single
    // classpath.
    for (String classPath : classPaths) {
      ImmutableSet<String> topLevelSymbols;
      topLevelSymbols = jarResolver.apply(classPath);

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
  Optional<DependencyCheckingJavacStep.SuggestBuildRules> createSuggestBuildFunction(
      BuildContext context,
      ImmutableSetMultimap<JavaLibraryRule, String> transitiveClasspathEntries,
      ImmutableSetMultimap<JavaLibraryRule, String> declaredClasspathEntries,
      final JarResolver jarResolver) {
    if (context.getBuildDependencies() != BuildDependencies.WARN_ON_TRANSITIVE) {
      return Optional.absent();
    }
    final Set<JavaLibraryRule> transitiveNotDeclaredDeps = Sets.difference(
        transitiveClasspathEntries.keySet(),
        declaredClasspathEntries.keySet());

    final ImmutableList<BuildRule> sortedTransitiveNotDeclaredDeps = ImmutableList.copyOf(
        TopologicalSort.sort(context.getDependencyGraph(),
            new Predicate<BuildRule>() {
              @Override
              public boolean apply(BuildRule input) {
                return transitiveNotDeclaredDeps.contains(input);
              }
            })).reverse();

    DependencyCheckingJavacStep.SuggestBuildRules suggestBuildRuleFn =
        new DependencyCheckingJavacStep.SuggestBuildRules() {
      @Override
      public ImmutableSet<String> apply(ImmutableSet<String> failedImports) {
        ImmutableSet.Builder<String> suggestedDeps = ImmutableSet.builder();

        Set<String> remainingImports = Sets.newHashSet(failedImports);

        for (BuildRule transitiveNotDeclaredDep : sortedTransitiveNotDeclaredDeps) {
          boolean ruleCanSeeDep = transitiveNotDeclaredDep.isVisibleTo(
              DefaultJavaLibraryRule.this.getBuildTarget());
          if (ruleCanSeeDep &&
              isMissingBuildRule(transitiveNotDeclaredDep, remainingImports, jarResolver)) {
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
  public void initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> abiKeyHash = onDiskBuildInfo.getHash(AbiRule.ABI_KEY_ON_DISK_METADATA);
    if (abiKeyHash.isPresent()) {
      setAbiKey(Suppliers.ofInstance(abiKeyHash.get()));
    } else {
      throw new IllegalStateException(String.format(
          "Should not be initializing %s from disk if the ABI key is not written.", this));
    }
  }

  @Override
  public Sha1HashCode getAbiKey() {
    Preconditions.checkState(isRuleBuilt(),
        "%s must be built before its ABI key can be returned.", this);
    return abiKeySupplier.get();
  }

  private void setAbiKey(Supplier<Sha1HashCode> abiKeySupplier) {
    Preconditions.checkState(this.abiKeySupplier == null, "abiKeySupplier should be set only once");
    this.abiKeySupplier = abiKeySupplier;
  }


  @VisibleForTesting
  void addResourceCommands(BuildContext context,
                           ImmutableList.Builder<Step> commands,
                           String outputDirectory,
                           JavaPackageFinder javaPackageFinder) {
    if (!resources.isEmpty()) {
      String targetPackageDir = javaPackageFinder.findJavaPackageForPath(
          getBuildTarget().getBasePathWithSlash())
          .replace('.', File.separatorChar);

      for (SourcePath rawResource : resources) {
        // If the path to the file defining this rule were:
        // "first-party/orca/lib-http/tests/com/facebook/orca/BUILD"
        //
        // And the value of resource were:
        // "first-party/orca/lib-http/tests/com/facebook/orca/protocol/base/batch_exception1.txt"
        //
        // Then javaPackageAsPath would be:
        // "com/facebook/orca/protocol/base/"
        //
        // And the path that we would want to copy to the classes directory would be:
        // "com/facebook/orca/protocol/base/batch_exception1.txt"
        //
        // Therefore, some path-wrangling is required to produce the correct string.

        String resource = Paths.normalizePathSeparator(rawResource.resolve(context).toString());
        String javaPackageAsPath = javaPackageFinder.findJavaPackageFolderForPath(resource);
        String relativeSymlinkPath;


        if (resource.startsWith(BuckConstant.BUCK_OUTPUT_DIRECTORY) ||
            resource.startsWith(BuckConstant.GEN_DIR) ||
            resource.startsWith(BuckConstant.BIN_DIR) ||
            resource.startsWith(BuckConstant.ANNOTATION_DIR)) {
          // Handle the case where we depend on the output of another BuildRule. In that case, just
          // grab the output and put in the same package as this target would be in.
          relativeSymlinkPath = String.format(
              "%s/%s", targetPackageDir, rawResource.resolve(context).getFileName());
        } else if ("".equals(javaPackageAsPath)) {
          // In this case, the project root is acting as the default package, so the resource path
          // works fine.
          relativeSymlinkPath = resource;
        } else {
          int lastIndex = resource.lastIndexOf(javaPackageAsPath);
          Preconditions.checkState(lastIndex >= 0,
              "Resource path %s must contain %s",
              resource,
              javaPackageAsPath);

          relativeSymlinkPath = resource.substring(lastIndex);
        }
        String target = outputDirectory + '/' + relativeSymlinkPath;
        MkdirAndSymlinkFileStep link = new MkdirAndSymlinkFileStep(resource, target);
        commands.add(link);
      }
    }
  }

  @Override
  public String getPathToOutputFile() {
    return outputJar.orNull();
  }

  public static Builder newJavaLibraryRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<DefaultJavaLibraryRule> implements
      SrcsAttributeBuilder, ResourcesAttributeBuilder {

    protected Set<String> srcs = Sets.newHashSet();
    protected Set<SourcePath> resources = Sets.newHashSet();
    protected final AnnotationProcessingParams.Builder annotationProcessingBuilder =
        new AnnotationProcessingParams.Builder();
    protected boolean exportDeps = false;
    protected JavacOptions.Builder javacOptions = JavacOptions.builder();
    protected Optional<String> proguardConfig = Optional.absent();

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public DefaultJavaLibraryRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(ruleResolver);
      javacOptions.setAnnotationProcessingData(processingParams);

      return new DefaultJavaLibraryRule(
          buildRuleParams,
          srcs,
          resources,
          proguardConfig,
          exportDeps,
          javacOptions.build());
    }

    public AnnotationProcessingParams.Builder getAnnotationProcessingBuilder() {
      return annotationProcessingBuilder;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      annotationProcessingBuilder.setOwnerTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addSrc(String src) {
      srcs.add(src);
      return this;
    }

    @Override
    public Builder addResource(SourcePath relativePathToResource) {
      resources.add(relativePathToResource);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    public Builder setProguardConfig(Optional<String> proguardConfig) {
      this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
      return this;
    }


    public Builder setSourceLevel(String sourceLevel) {
      javacOptions.setSourceLevel(sourceLevel);
      return this;
    }

    public Builder setTargetLevel(String targetLevel) {
      javacOptions.setTargetLevel(targetLevel);
      return this;
    }

    public Builder setExportDeps(boolean exportDeps) {
      this.exportDeps = exportDeps;
      return this;
    }
  }
}
