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

import com.facebook.buck.android.HasAndroidResourceDeps;
import com.facebook.buck.android.UberRDotJavaUtil;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.ResourcesAttributeBuilder;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.util.BuckConstant;
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
 *   ],
 * )
 * </pre>
 * Then this would compile {@code FeedStoryRenderer.java} against Guava and the classes generated
 * from the {@code //src/com/facebook/feed/model:model} rule.
 */
public class DefaultJavaLibraryRule extends AbstractCachingBuildRule
    implements JavaLibraryRule, HasJavaSrcs, HasClasspathEntries {

  private final ImmutableSortedSet<String> srcs;

  private final ImmutableSortedSet<String> resources;

  private final Optional<String> outputJar;

  private final List<String> inputsToConsiderForCachingPurposes;

  private final Optional<String> proguardConfig;


  private final boolean exportDeps;

  private final Supplier<ImmutableSet<String>> outputClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<BuildRule, String>>
      transitiveClasspathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<BuildRule, String>>
      declaredClasspathEntriesSupplier;

  protected final JavacOptions javacOptions;

  /**
   * Function for opening a JAR and returning all symbols that can be referenced from inside of that
   * jar.
   */
  @VisibleForTesting
  static interface JarResolver extends
      Function<String, ImmutableSet<String>> {}

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
   * This is set in {@link #buildInternal(com.facebook.buck.rules.BuildContext)} and is available to subclasses.
   */
  protected ImmutableList<HasAndroidResourceDeps> androidResourceDeps;

  protected DefaultJavaLibraryRule(BuildRuleParams buildRuleParams,
                                   Set<String> srcs,
                                   Set<String> resources,
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
    inputsToConsiderForCachingPurposes = ImmutableList.<String>builder()
        .addAll(this.srcs)
        .addAll(this.resources)
        .build();

    outputClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSet<String>>() {
          @Override
          public ImmutableSet<String> get() {
            ImmutableSet<String> outputClasspathEntries;

            // If this java_library exports its dependencies then just return the transitive
            // dependencies.
            if (DefaultJavaLibraryRule.this.exportDeps) {
              outputClasspathEntries = ImmutableSet.copyOf(
                  getTransitiveClasspathEntries().values());
            } else if (outputJar.isPresent()) {
              outputClasspathEntries = ImmutableSet.of(getPathToOutputFile());
            } else {
              outputClasspathEntries = ImmutableSet.of();
            }

            return outputClasspathEntries;
          }
        });

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<BuildRule, String>>() {
          @Override
          public ImmutableSetMultimap<BuildRule, String> get() {
            final ImmutableSetMultimap.Builder<BuildRule, String> classpathEntries =
                ImmutableSetMultimap.builder();
            ImmutableSetMultimap<BuildRule, String> classpathEntriesForDeps =
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
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<BuildRule, String>>() {
          @Override
          public ImmutableSetMultimap<BuildRule, String> get() {
            final ImmutableSetMultimap.Builder<BuildRule, String> classpathEntries =
               ImmutableSetMultimap.builder();

            Iterable<JavaLibraryRule> javaLibraryDeps = Iterables.filter(
                Sets.union(getDeps(), ImmutableSet.of(DefaultJavaLibraryRule.this)),
                JavaLibraryRule.class);

            for (JavaLibraryRule rule : javaLibraryDeps) {
              classpathEntries.putAll(rule, rule.getOutputClasspathEntries());
            }
            return classpathEntries.build();
          }
        });
  }

  /**
   * @param outputDirectory Directory to write class files to
   * @param javaSourceFilePaths .java files to compile: may be empty
   * @param transitiveClasspathEntries Classpaths of all transitive dependencies.
   * @param declaredClasspathEntries Classpaths of all declared dependencies.
   * @param javacOptions options to use when compiling code.
   * @param suggestBuildRules Function to convert from missing symbols to the suggested rules.
   * @return commands to compile the specified inputs
   */
  private static ImmutableList<Step> createCommandsForJavac(
      String outputDirectory,
      final SortedSet<String> javaSourceFilePaths,
      ImmutableSet<String> transitiveClasspathEntries,
      ImmutableSet<String> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<String> invokingRule,
      BuildDependencies buildDependencies,
      Optional<DependencyCheckingJavacStep.SuggestBuildRules> suggestBuildRules) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Only run javac if there are .java files to compile.
    if (!javaSourceFilePaths.isEmpty()) {
      Step javac = new DependencyCheckingJavacStep(
          outputDirectory,
          javaSourceFilePaths,
          transitiveClasspathEntries,
          declaredClasspathEntries,
          javacOptions,
          invokingRule,
          buildDependencies,
          suggestBuildRules);

      commands.add(javac);
    }

    return commands.build();
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

  @Override
  public boolean isAndroidRule() {
    return false;
  }

  @Override
  public boolean isLibrary() {
    return true;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    RuleKey.Builder builder = super.ruleKeyBuilder()
        .set("srcs", srcs)
        .set("resources", resources)
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
  public ImmutableSortedSet<String> getJavaSrcs() {
    return srcs;
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<String> getOutputClasspathEntries() {
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
  protected List<String> getInputsToCompareToOutput(BuildContext context) {
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
  protected final List<Step> buildInternal(BuildContext context) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    BuildTarget buildTarget = getBuildTarget();

    JavacOptions javacOptions = this.javacOptions;
    // Only override the bootclasspath if this rule is supposed to compile Android code.
    if (isAndroidRule()) {
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

    ImmutableSetMultimap<BuildRule, String> transitiveClasspathEntries =
        getTransitiveClasspathEntries();
    ImmutableSetMultimap<BuildRule, String> declaredClasspathEntries =
        getDeclaredClasspathEntries();

    // If this rule depends on AndroidResourceRules, then we need to include the compiled R.java
    // files on the classpath when compiling this rule.
    if (dependsOnAndroidResourceRules) {
      ImmutableSetMultimap.Builder<BuildRule, String> transitiveClasspathEntriesWithRDotJava =
          ImmutableSetMultimap.builder();
      transitiveClasspathEntriesWithRDotJava.putAll(transitiveClasspathEntries);

      ImmutableSetMultimap.Builder<BuildRule, String> declaredClasspathEntriesWithRDotJava =
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
    List<Step> javac = createCommandsForJavac(
        outputDirectory,
        srcs,
        ImmutableSet.copyOf(transitiveClasspathEntries.values()),
        ImmutableSet.copyOf(declaredClasspathEntries.values()),
        javacOptions,
        Optional.of(getFullyQualifiedName()),
        context.getBuildDependencies(),
        suggestBuildRule);
    commands.addAll(javac);


    // If there are resources, then link them to the appropriate place in the classes directory.
    addResourceCommands(commands, outputDirectory, context.getJavaPackageFinder());

    if (outputJar.isPresent()) {
      commands.add(new MakeCleanDirectoryStep(getOutputJarDirPath(getBuildTarget())));
      commands.add(new JarDirectoryStep(
          outputJar.get(),
          Collections.singleton(outputDirectory),
          /* mainClass */ null,
          /* manifestFile */ null));
    }

    return commands.build();
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
    ImmutableSet<String> classPaths = getTransitiveClasspathEntries().get(transitiveNotDeclaredDep);
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
      ImmutableSetMultimap<BuildRule, String> transitiveClasspathEntries,
      ImmutableSetMultimap<BuildRule, String> declaredClasspathEntries,
      final JarResolver jarResolver) {
    if (context.getBuildDependencies() != BuildDependencies.WARN_ON_TRANSITIVE) {
      return Optional.absent();
    }
    final Set<BuildRule> transitiveNotDeclaredDeps = Sets.difference(
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


  @VisibleForTesting
  void addResourceCommands(ImmutableList.Builder<Step> commands,
                           String outputDirectory,
                           JavaPackageFinder javaPackageFinder) {
    if (!resources.isEmpty()) {
      for (String resource : resources) {
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
        String javaPackageAsPath = javaPackageFinder.findJavaPackageFolderForPath(resource);
        String relativeSymlinkPath;
        if ("".equals(javaPackageAsPath)) {
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

  public static Builder newJavaLibraryRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder<DefaultJavaLibraryRule> implements
      SrcsAttributeBuilder, ResourcesAttributeBuilder {

    protected Set<String> srcs = Sets.newHashSet();
    protected Set<String> resources = Sets.newHashSet();
    protected final AnnotationProcessingParams.Builder annotationProcessingBuilder =
        new AnnotationProcessingParams.Builder();
    protected boolean exportDeps = false;
    protected JavacOptions.Builder javacOptions = JavacOptions.builder();
    protected Optional<String> proguardConfig = Optional.absent();

    protected Builder() {}

    @Override
    public DefaultJavaLibraryRule build(BuildRuleBuilderParams buildRuleBuilderParams) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(buildRuleBuilderParams);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(buildRuleBuilderParams);
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
    public Builder addDep(String dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addSrc(String src) {
      srcs.add(src);
      return this;
    }

    @Override
    public Builder addResource(String relativePathToResource) {
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
