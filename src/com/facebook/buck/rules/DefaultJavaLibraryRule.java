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

package com.facebook.buck.rules;

import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.JarDirectoryCommand;
import com.facebook.buck.shell.JavacInMemoryCommand;
import com.facebook.buck.shell.JavacOptionsUtil;
import com.facebook.buck.shell.MakeCleanDirectoryCommand;
import com.facebook.buck.shell.MkdirAndSymlinkFileCommand;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

  private final Optional<File> outputJar;

  private final List<String> inputsToConsiderForCachingPurposes;

  private final AnnotationProcessingParams annotationProcessingParams;

  private final Supplier<ImmutableSet<String>> classpathEntriesSupplier;

  private final Supplier<ImmutableSetMultimap<BuildRule, String>> classpathEntriesMapSupplier;

  @Nullable
  private final String proguardConfig;

  private final String sourceLevel;
  private final String targetLevel;

  /**
   * This is set in {@link #buildInternal(BuildContext)} and is available to subclasses.
   */
  protected ImmutableList<AndroidResourceRule> androidResourceDeps;

  protected DefaultJavaLibraryRule(BuildRuleParams buildRuleParams,
                                   Set<String> srcs,
                                   Set<String> resources,
                                   @Nullable String proguardConfig,
                                   AnnotationProcessingParams annotationProcessingParams) {
    this(
        buildRuleParams,
        srcs,
        resources,
        proguardConfig,
        annotationProcessingParams,
        JavacOptionsUtil.DEFAULT_SOURCE_LEVEL,
        JavacOptionsUtil.DEFAULT_TARGET_LEVEL
    );
  }


  protected DefaultJavaLibraryRule(BuildRuleParams buildRuleParams,
                                   Set<String> srcs,
                                   Set<String> resources,
                                   @Nullable String proguardConfig,
                                   AnnotationProcessingParams annotationProcessingParams,
                                   String sourceLevel,
                                   String targetLevel) {
    super(buildRuleParams);
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.annotationProcessingParams = Preconditions.checkNotNull(annotationProcessingParams);
    this.proguardConfig = proguardConfig;
    this.sourceLevel = sourceLevel;
    this.targetLevel = targetLevel;

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      File file = new File(getOutputJarPath(getBuildTarget()));
      this.outputJar = Optional.of(file);
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

    classpathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSet<String>>() {
          @Override
          public ImmutableSet<String> get() {
            return ImmutableSet.copyOf(getClasspathEntriesMap().values());
          }
        });

    classpathEntriesMapSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<BuildRule, String>>() {
          @Override
          public ImmutableSetMultimap<BuildRule, String> get() {
            final ImmutableSetMultimap.Builder<BuildRule, String> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.putAll(getClasspathEntriesForDeps());

            // Only add ourselves to the classpath if there's a jar to be built.
            if (outputJar.isPresent()) {
              classpathEntries.put(DefaultJavaLibraryRule.this, getOutput().getPath());
            }
            return classpathEntries.build();
          }
        });
  }

  /**
   * @param outputDirectory Directory to write class files to
   * @param javaSourceFilePaths .java files to compile: may be empty
   * @param classpathEntries to include on the -classpath when compiling with javac
   * @param annotationProcessingData to process JSR269 java annotations
   * @return commands to compile the specified inputs
   */
  private static ImmutableList<Command> createCommandsForJavac(
      String outputDirectory,
      final SortedSet<String> javaSourceFilePaths,
      Set<String> classpathEntries,
      Supplier<String> bootclasspathSupplier,
      AnnotationProcessingData annotationProcessingData,
      String sourceLevel,
      String targetLevel) {
    ImmutableList.Builder<Command> commands = ImmutableList.builder();

    // Only run javac if there are .java files to compile.
    if (!javaSourceFilePaths.isEmpty()) {
      Command javac = new JavacInMemoryCommand(
          outputDirectory,
          javaSourceFilePaths,
          classpathEntries,
          bootclasspathSupplier,
          annotationProcessingData,
          sourceLevel,
          targetLevel);
      commands.add(javac);
    }

    return commands.build();
  }

  private static String getOutputJarPath(BuildTarget target) {
    return String.format(
        "%s/%slib__%s__output/%s.jar",
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName(),
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
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("srcs", srcs)
        .set("resources", resources)
        .set("classpathEntries", getClasspathEntries())
        .set("inputsToConsiderForCachingPurposes", inputsToConsiderForCachingPurposes)
        .set("isAndroidLibrary", isAndroidRule())
        .set("sourceLevel", sourceLevel)
        .set("targetLevel", targetLevel);
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
  public ImmutableSetMultimap<BuildRule, String> getClasspathEntriesMap() {
    return classpathEntriesMapSupplier.get();
  }

  @Override
  public ImmutableSet<String> getClasspathEntries() {
    return classpathEntriesSupplier.get();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return annotationProcessingParams;
  }

  @Nullable
  public String getProguardConfig() {
    return proguardConfig;
  }

  @Override
  @Nullable
  protected List<String> getInputsToCompareToOutput(BuildContext context) {
    return inputsToConsiderForCachingPurposes;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under {@link BuckConstant#BIN_DIR}.
   */
  @Override
  protected final List<Command> buildInternal(BuildContext context) throws IOException {
    ImmutableList.Builder<Command> commands = ImmutableList.builder();
    BuildTarget buildTarget = getBuildTarget();

    // If this rule depends on AndroidResourceRules, then we need to generate the R.java files that
    // this rule needs in order to be able to compile itself.
    androidResourceDeps = AndroidResourceRule.getAndroidResourceDeps(this,
        context.getDependencyGraph());
    boolean dependsOnAndroidResourceRules = !androidResourceDeps.isEmpty();
    if (dependsOnAndroidResourceRules) {
      UberRDotJavaUtil.createDummyRDotJavaFiles(androidResourceDeps, buildTarget, commands);
    }

    Set<String> classpathEntries = getClasspathEntries();
    // If this rule depends on AndroidResourceRules, then we need to include the compiled R.java
    // files on the classpath when compiling this rule.
    if (dependsOnAndroidResourceRules) {
      classpathEntries = Sets.union(
          ImmutableSet.of(UberRDotJavaUtil.getRDotJavaBinFolder(buildTarget)),
          classpathEntries);
    }

    // Only override the bootclasspath if this rule is supposed to compile Android code.
    Supplier<String> bootclasspathSupplier;
    if (isAndroidRule()) {
      bootclasspathSupplier = context.getAndroidBootclasspathSupplier();
    } else {
      bootclasspathSupplier = Suppliers.ofInstance(null);
    }

    // Javac requires that the root directory for generated sources already exist.
    String annotationGenFolder = annotationProcessingParams.getGeneratedSourceFolderName();
    if (annotationGenFolder != null) {
      MakeCleanDirectoryCommand mkdirGeneratedSources =
          new MakeCleanDirectoryCommand(annotationGenFolder);
      commands.add(mkdirGeneratedSources);
    }

    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    String outputDirectory = getClassesDir(getBuildTarget());
    commands.add(new MakeCleanDirectoryCommand(outputDirectory));

    // This adds the javac command, along with any supporting commands.
    List<Command> javac = createCommandsForJavac(
        outputDirectory,
        srcs,
        classpathEntries,
        bootclasspathSupplier,
        annotationProcessingParams,
        sourceLevel,
        targetLevel);

    commands.addAll(javac);

    // If there are resources, then link them to the appropriate place in the classes directory.
    addResourceCommands(commands, outputDirectory, context.getJavaPackageFinder());

    if (outputJar.isPresent()) {
      commands.add(new MakeCleanDirectoryCommand(outputJar.get().getParent()));
      commands.add(new JarDirectoryCommand(
          outputJar.get().getPath(), Collections.singleton(outputDirectory), null, null));
    }

    return commands.build();
  }

  @VisibleForTesting
  void addResourceCommands(ImmutableList.Builder<Command> commands,
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

        MkdirAndSymlinkFileCommand link = new MkdirAndSymlinkFileCommand(resource, target);
        commands.add(link);
      }
    }
  }

  @Override
  public File getOutput() {
    return outputJar.orNull();
  }

  public static Builder newJavaLibraryRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder implements SrcsAttributeBuilder,
      ResourcesAttributeBuilder {

    protected Set<String> srcs = Sets.newHashSet();
    protected Set<String> resources = Sets.newHashSet();
    protected final AnnotationProcessingParams.Builder annotationProcessingBuilder =
        new AnnotationProcessingParams.Builder();
    protected String sourceLevel = JavacOptionsUtil.DEFAULT_SOURCE_LEVEL;
    protected String targetLevel = JavacOptionsUtil.DEFAULT_TARGET_LEVEL;

    @Nullable
    protected String proguardConfig = null;

    protected Builder() {}

    @Override
    public DefaultJavaLibraryRule build(Map<String, BuildRule> buildRuleIndex) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(buildRuleIndex);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(buildRuleIndex);

      return new DefaultJavaLibraryRule(
          buildRuleParams,
          srcs,
          resources,
          proguardConfig,
          processingParams,
          sourceLevel,
          targetLevel);
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

    public Builder setProguardConfig(String proguardConfig) {
      this.proguardConfig = proguardConfig;
      return this;
    }

    public Builder setSourceLevel(String sourceLevel) {
      this.sourceLevel = sourceLevel;
      return this;
    }

    public Builder setTargetLevel(String targetLevel) {
      this.targetLevel = targetLevel;
      return this;
    }
  }
}
