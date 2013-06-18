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

import static com.facebook.buck.util.BuckConstant.BIN_DIR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibraryRule;
import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.Verbosity;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DefaultJavaLibraryRuleTest {
  private static final String ANNOTATION_SCENARIO_TARGET =
      "//android/java/src/com/facebook:fb";
  private static final String ANNOTATION_SCENARIO_GEN_PATH =
      BuckConstant.ANNOTATION_DIR + "/android/java/src/com/facebook/__fb_gen__";

  @Test
  public void testAddResourceCommandsWithBuildFileParentOfSrcDirectory() {
    // Files:
    // android/java/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//android/java", "resources", new File("android/java/BUILD"));
    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of();
    ImmutableSet<BuildTargetPattern> visibilityPatterns = ImmutableSet.of();
    DefaultJavaLibraryRule javaRule = new DefaultJavaLibraryRule(
        new BuildRuleParams(buildTarget, deps, visibilityPatterns),
        ImmutableSet.<String>of() /* srcs */,
        ImmutableSet.of(
            "android/java/src/com/facebook/base/data.json",
            "android/java/src/com/facebook/common/util/data.json"),
        /* proguardConfig */ Optional.<String>absent(),
        /* exportDeps */ false,
        JavacOptions.DEFAULTS
        );

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();
    javaRule.addResourceCommands(
        commands, BIN_DIR + "/android/java/lib__resources__classes", javaPackageFinder);
    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            "android/java/src/com/facebook/base/data.json",
            BIN_DIR + "/android/java/lib__resources__classes/com/facebook/base/data.json"),
        new MkdirAndSymlinkFileStep(
            "android/java/src/com/facebook/common/util/data.json",
            BIN_DIR + "/android/java/lib__resources__classes/com/facebook/common/util/data.json"));
    MoreAsserts.assertListEquals(expected, commands.build());
    EasyMock.verify(javaPackageFinder);
  }

  @Test
  public void testAddResourceCommandsWithBuildFileParentOfJavaPackage() {
    // Files:
    // android/java/src/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//android/java/src", "resources", new File("android/java/src/BUILD"));
    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of();
    ImmutableSet<BuildTargetPattern> visibilityPatterns = ImmutableSet.of();
    DefaultJavaLibraryRule javaRule = new DefaultJavaLibraryRule(
        new BuildRuleParams(buildTarget, deps, visibilityPatterns),
        ImmutableSet.<String>of() /* srcs */,
        ImmutableSet.of(
            "android/java/src/com/facebook/base/data.json",
            "android/java/src/com/facebook/common/util/data.json"),
        /* proguargConfig */ Optional.<String>absent(),
        /* exportDeps */ false,
        JavacOptions.DEFAULTS
        );

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();
    javaRule.addResourceCommands(
        commands, BIN_DIR + "/android/java/src/lib__resources__classes", javaPackageFinder);
    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            "android/java/src/com/facebook/base/data.json",
            BIN_DIR + "/android/java/src/lib__resources__classes/com/facebook/base/data.json"),
        new MkdirAndSymlinkFileStep(
            "android/java/src/com/facebook/common/util/data.json",
            BIN_DIR + "/android/java/src/lib__resources__classes/com/facebook/common/util/data.json"));
    MoreAsserts.assertListEquals(expected, commands.build());
    EasyMock.verify(javaPackageFinder);
  }

  @Test
  public void testAddResourceCommandsWithBuildFileInJavaPackage() {
    // Files:
    // android/java/src/com/facebook/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//android/java/src/com/facebook",
        "resources",
        new File("android/java/src/com/facebook/BUILD"));
    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of();
    ImmutableSet<BuildTargetPattern> visibilityPatterns = ImmutableSet.of();
    DefaultJavaLibraryRule javaRule = new DefaultJavaLibraryRule(
        new BuildRuleParams(buildTarget, deps, visibilityPatterns),
        ImmutableSet.<String>of() /* srcs */,
        ImmutableSet.of(
            "android/java/src/com/facebook/base/data.json",
            "android/java/src/com/facebook/common/util/data.json"),
        /* proguargConfig */ Optional.<String>absent(),
        /* exportDeps */ false,
        JavacOptions.DEFAULTS);

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();
    javaRule.addResourceCommands(
        commands,
        BIN_DIR + "/android/java/src/com/facebook/lib__resources__classes",
        javaPackageFinder);
    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            "android/java/src/com/facebook/base/data.json",
            BIN_DIR + "/android/java/src/com/facebook/lib__resources__classes/com/facebook/base/data.json"),
        new MkdirAndSymlinkFileStep(
            "android/java/src/com/facebook/common/util/data.json",
            BIN_DIR + "/android/java/src/com/facebook/lib__resources__classes/com/facebook/common/util/data.json"));
    MoreAsserts.assertListEquals(expected, commands.build());
    EasyMock.verify(javaPackageFinder);
  }

  /** Make sure that when isAndroidLibrary is true, that the Android bootclasspath is used. */
  @Test
  public void testBuildInternalWithAndroidBootclasspath() throws IOException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//android/java/src/com/facebook:fb");
    String src = "android/java/src/com/facebook/Main.java";
    BuildRuleBuilderParams buildRuleBuilderParams = new BuildRuleBuilderParams();
    DefaultJavaLibraryRule javaLibrary = buildRuleBuilderParams.buildAndAddToIndex(
        AndroidLibraryRule.newAndroidLibraryRuleBuilder()
        .setBuildTarget(buildTarget)
        .addSrc(src));

    String bootclasspath = "effects.jar:maps.jar:usb.jar:";
    BuildContext context = createBuildContext(javaLibrary, bootclasspath);

    List<Step> steps = javaLibrary.buildInternal(context);

    // Find the JavacInMemoryCommand and verify its bootclasspath.
    Step step = Iterables.find(steps, new Predicate<Step>() {
      @Override
      public boolean apply(Step command) {
        return command instanceof JavacInMemoryStep;
      }
    });
    assertNotNull("Expected a JavacInMemoryCommand in the command list.", step);
    JavacInMemoryStep javac = (JavacInMemoryStep) step;
    assertEquals("Should compile Main.java rather than generated R.java.",
        ImmutableSet.of(src),
        javac.getSrcs());

    EasyMock.verify(context);
  }

  /**
   * Verify that no annotation options are there if we do not add an
   * annotation processor.
   */
  @Test
  public void testNoAnnotationProcessor() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    for (String parameter : parameters) {
      assertNotEquals("Expected no -processorpath parameters", parameter, "-processorpath");
      assertNotEquals("Expected no -processor parameters", parameter, "-processor");
      assertNotEquals("Expected no -s parameters", parameter, "-s");
      assertFalse("Expected no annotation options", parameter.startsWith("-A"));
    }
  }

  /**
   * Verify adding an annotation processor java binary.
   */
  @Test
  public void testAddAnnotationProcessorJavaBinary() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_JAVA_BINARY);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    MoreAsserts.assertContainsOne(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor"));
    assertEquals(
        "Expected '-s " + ANNOTATION_SCENARIO_GEN_PATH + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(ANNOTATION_SCENARIO_GEN_PATH));

    for (String parameter : parameters) {
      assertFalse("Expected no annotation options", parameter.startsWith("-A"));
    }
  }

  /**
   * Verify adding an annotation processor prebuilt jar.
   */
  @Test
  public void testAddAnnotationProcessorPrebuiltJar() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_PREBUILT_JAR);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    MoreAsserts.assertContainsOne(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);
  }

  /**
   * Verify adding an annotation processor java library.
   */
  @Test
  public void testAddAnnotationProcessorJavaLibrary() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_PREBUILT_JAR);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    MoreAsserts.assertContainsOne(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);
  }

  /**
   * Verify adding multiple annotation processors.
   */
  @Test
  public void testAddAnnotationProcessorJar() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_PREBUILT_JAR);
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_JAVA_BINARY);
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_JAVA_LIBRARY);

    scenario.getAnnotationProcessingParamsBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    MoreAsserts.assertContainsOne(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);
  }

  @Test
  public void testAndroidAnnotation() throws IOException {
    BuildRuleBuilderParams buildRuleBuilderParams = new BuildRuleBuilderParams();

    BuildTarget processorTarget = BuildTargetFactory.newInstance("//java/processor:processor");
    buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(processorTarget)
        .addSrc("java/processor/processor.java"));

    BuildTarget libTarget = BuildTargetFactory.newInstance("//java/lib:lib");
    AndroidLibraryRule.Builder builder = new AndroidLibraryRule.Builder()
        .setBuildTarget(libTarget);
    builder.getAnnotationProcessingBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"))
        .addProcessorBuildTarget(processorTarget);

    AndroidLibraryRule rule = (AndroidLibraryRule) buildRuleBuilderParams.buildAndAddToIndex(builder);

    AnnotationProcessingData processingData = rule.getAnnotationProcessingData();
    assertNotNull(processingData.getGeneratedSourceFolderName());
  }

  @Test
  public void testGetClasspathEntriesMap() {
    BuildRuleBuilderParams buildRuleBuilderParams = new BuildRuleBuilderParams();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    JavaLibraryRule libraryOne = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryOneTarget)
        .addSrc("java/src/com/libone/Bar.java"));

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    JavaLibraryRule libraryTwo = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryTwoTarget)
        .addSrc("java/src/com/libtwo/Foo.java")
        .addDep(BuildTargetFactory.newInstance("//:libone")));

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    JavaLibraryRule parent = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(parentTarget)
        .addSrc("java/src/com/parent/Meh.java")
        .addDep(BuildTargetFactory.newInstance("//:libtwo")));

    assertEquals(ImmutableSetMultimap.of(
        libraryOne, "buck-out/gen/lib__libone__output/libone.jar",
        libraryTwo, "buck-out/gen/lib__libtwo__output/libtwo.jar",
        parent, "buck-out/gen/lib__parent__output/parent.jar"),
        parent.getTransitiveClasspathEntries());
  }

  /**
   * Verify adding an annotation processor java binary with options.
   */
  @Test
  public void testAddAnnotationProcessorWithOptions() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_JAVA_BINARY);

    scenario.getAnnotationProcessingParamsBuilder().addAllProcessors(ImmutableList.of("MyProcessor"));
    scenario.getAnnotationProcessingParamsBuilder().addParameter("MyParameter");
    scenario.getAnnotationProcessingParamsBuilder().addParameter("MyKey=MyValue");
    scenario.getAnnotationProcessingParamsBuilder().setProcessOnly(true);

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    MoreAsserts.assertContainsOne(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);
    MoreAsserts.assertContainsOne(parameters, "-proc:only");

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor"));
    assertEquals(
        "Expected '-s " + ANNOTATION_SCENARIO_GEN_PATH + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(ANNOTATION_SCENARIO_GEN_PATH));

    MoreAsserts.assertContainsOne(parameters, "-AMyParameter");
    MoreAsserts.assertContainsOne(parameters, "-AMyKey=MyValue");
  }

  @Test
  public void testExportDeps() {
    BuildRuleBuilderParams buildRuleBuilderParams = new BuildRuleBuilderParams();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    JavaLibraryRule libraryOne = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryOneTarget)
        .addSrc("java/src/com/libone/Bar.java"));

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    JavaLibraryRule libraryTwo = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryTwoTarget)
        .addSrc("java/src/com/libtwo/Foo.java")
        .addDep(BuildTargetFactory.newInstance("//:libone"))
        .setExportDeps(true));

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    JavaLibraryRule parent = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(parentTarget)
        .addSrc("java/src/com/parent/Meh.java")
        .addDep(BuildTargetFactory.newInstance("//:libtwo")));

    assertEquals(ImmutableSet.of("buck-out/gen/lib__libone__output/libone.jar"),
        libraryOne.getOutputClasspathEntries());

    assertEquals(
        ImmutableSet.of("buck-out/gen/lib__libone__output/libone.jar",
            "buck-out/gen/lib__libtwo__output/libtwo.jar"),
        libraryTwo.getOutputClasspathEntries());

    ImmutableSetMultimap.Builder<BuildRule, String> expected = ImmutableSetMultimap.builder();
    expected.put(parent, "buck-out/gen/lib__parent__output/parent.jar");
    expected.putAll(libraryTwo,
        "buck-out/gen/lib__libone__output/libone.jar",
        "buck-out/gen/lib__libtwo__output/libtwo.jar");

    assertEquals(expected.build(), parent.getDeclaredClasspathEntries());
  }

  @Test
  public void testEmptySuggestBuildFunction() {
    BuildRuleBuilderParams buildRuleBuilderParams = new BuildRuleBuilderParams();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    DefaultJavaLibraryRule libraryOne = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryOneTarget)
        .addSrc("java/src/com/libone/bar.java"));

    BuildContext context = createSuggestContext(buildRuleBuilderParams,
        BuildDependencies.FIRST_ORDER_ONLY);

    ImmutableSetMultimap<BuildRule, String> classpathEntries =
        libraryOne.getTransitiveClasspathEntries();

    assertEquals(
        Optional.<DependencyCheckingJavacStep.SuggestBuildRules>absent(),
        libraryOne.createSuggestBuildFunction(context,
            classpathEntries,
            classpathEntries,
            createJarResolver(/* classToSymbols */ImmutableMap.<String, String>of())));

    EasyMock.verify(context);
  }

  @Test
  public void testSuggsetDepsReverseTopoSortRespected() {
    BuildRuleBuilderParams buildRuleBuilderParams = new BuildRuleBuilderParams();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    DefaultJavaLibraryRule libraryOne = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryOneTarget)
        .addSrc("java/src/com/libone/Bar.java")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    DefaultJavaLibraryRule libraryTwo = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(libraryTwoTarget)
        .addSrc("java/src/com/libtwo/Foo.java")
        .addDep(BuildTargetFactory.newInstance("//:libone")));

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    DefaultJavaLibraryRule parent = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(parentTarget)
        .addSrc("java/src/com/parent/Meh.java")
        .addDep(BuildTargetFactory.newInstance("//:libtwo"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget grandparentTarget = BuildTargetFactory.newInstance("//:grandparent");
    DefaultJavaLibraryRule grandparent = buildRuleBuilderParams.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(grandparentTarget)
        .addSrc("java/src/com/parent/OldManRiver.java")
        .addDep(BuildTargetFactory.newInstance("//:parent")));

    BuildContext context = createSuggestContext(buildRuleBuilderParams,
        BuildDependencies.WARN_ON_TRANSITIVE);

    ImmutableSetMultimap<BuildRule, String> transitive =
        parent.getTransitiveClasspathEntries();

    ImmutableMap<String, String> classToSymbols = ImmutableMap.of(
        Iterables.getFirst(transitive.get(parent), null), "com.facebook.Foo",
        Iterables.getFirst(transitive.get(libraryOne), null), "com.facebook.Bar",
        Iterables.getFirst(transitive.get(libraryTwo), null), "com.facebook.Foo");

    Optional<DependencyCheckingJavacStep.SuggestBuildRules> suggestFn =
        grandparent.createSuggestBuildFunction(context,
            transitive,
            /* declaredClasspathEntries */ ImmutableSetMultimap.<BuildRule, String>of(),
            createJarResolver(classToSymbols));

    assertTrue(suggestFn.isPresent());
    assertEquals(ImmutableSet.of("//:parent", "//:libone"),
        suggestFn.get().apply(ImmutableSet.of("com.facebook.Foo", "com.facebook.Bar")));

    EasyMock.verify(context);
  }


  // Utilities

  private DefaultJavaLibraryRule.JarResolver createJarResolver(
      final ImmutableMap<String, String> classToSymbols) {

    ImmutableSetMultimap.Builder<String, String> resolveMapBuilder =
        ImmutableSetMultimap.builder();

    for (Map.Entry<String, String> entry : classToSymbols.entrySet()) {
      String fullyQualified = entry.getValue();
      String packageName = fullyQualified.substring(0, fullyQualified.lastIndexOf('.'));
      String className = fullyQualified.substring(fullyQualified.lastIndexOf('.'));
      resolveMapBuilder.putAll(entry.getKey(), fullyQualified, packageName, className);
    }

    final ImmutableSetMultimap<String, String> resolveMap = resolveMapBuilder.build();

    return new DefaultJavaLibraryRule.JarResolver() {
      @Override
      public ImmutableSet<String> apply(String input) {
        if (resolveMap.containsKey(input)) {
          return resolveMap.get(input);
        } else {
          return ImmutableSet.of();
        }
      }
    };
  }

  private JavaPackageFinder createJavaPackageFinder() {
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    EasyMock.expect(javaPackageFinder.findJavaPackageFolderForPath(
        "android/java/src/com/facebook/base/data.json"))
        .andReturn("com/facebook/base/");
    EasyMock.expect(javaPackageFinder.findJavaPackageFolderForPath(
        "android/java/src/com/facebook/common/util/data.json"))
        .andReturn("com/facebook/common/util/");

    EasyMock.replay(javaPackageFinder);
    return javaPackageFinder;
  }

  private BuildContext createSuggestContext(BuildRuleBuilderParams buildRuleBuilderParams,
                                            BuildDependencies buildDependencies) {
    DependencyGraph graph = RuleMap.createGraphFromBuildRules(buildRuleBuilderParams);

    BuildContext context = EasyMock.createMock(BuildContext.class);
    EasyMock.expect(context.getDependencyGraph()).andReturn(graph);
    EasyMock.expectLastCall().anyTimes();

    EasyMock.expect(context.getBuildDependencies()).andReturn(buildDependencies).anyTimes();

    EasyMock.replay(context);

    return context;
  }

  private BuildContext createBuildContext(DefaultJavaLibraryRule javaLibrary,
                                          String bootclasspath) {
    DependencyGraph graph = RuleMap.createGraphFromSingleRule(javaLibrary);

    BuildContext context = EasyMock.createMock(BuildContext.class);
    EasyMock.expect(context.getDependencyGraph()).andReturn(graph);
    EasyMock.expectLastCall().anyTimes();

    EasyMock.expect(context.getAndroidBootclasspathSupplier()).andReturn(Suppliers.ofInstance(
        bootclasspath));
    EasyMock.expect(context.getJavaPackageFinder()).andReturn(
        EasyMock.createMock(JavaPackageFinder.class));
    EasyMock.expect(context.getBuildDependencies()).andReturn(BuildDependencies.TRANSITIVE);
    EasyMock.expectLastCall().anyTimes();

    EasyMock.replay(context);

    return context;
  }

  private enum AnnotationProcessorTarget {
    VALID_PREBUILT_JAR("//tools/java/src/com/someone/library:prebuilt-processors") {
      @Override
      public BuildRule createRule(BuildTarget target) {
        return new PrebuiltJarRule(
            createBuildRuleParams(target),
            "MyJar",
            Optional.<String>absent(),
            Optional.<String>absent());
      }
    },
    VALID_JAVA_BINARY("//tools/java/src/com/facebook/annotations:custom-processors") {
      @Override
      public BuildRule createRule(BuildTarget target) {
        return new JavaBinaryRule(
            createBuildRuleParams(target),
            "com.facebook.Main",
            null,
            null,
            new DefaultDirectoryTraverser());
      }
    },
    VALID_JAVA_LIBRARY("//tools/java/src/com/facebook/somejava:library") {
      @Override
      public BuildRule createRule(BuildTarget target) {
        return new DefaultJavaLibraryRule(
            createBuildRuleParams(target),
            ImmutableSet.<String>of("MyClass.java"),
            ImmutableSet.<String>of(),
            Optional.of("MyProguardConfig"),
            /* exportDeps */ false,
            JavacOptions.DEFAULTS);
      }
    };

    private final String targetName;

    private AnnotationProcessorTarget(String targetName) {
      this.targetName = targetName;
    }

    protected BuildRuleParams createBuildRuleParams(BuildTarget target) {
      return new BuildRuleParams(
          target,
          ImmutableSortedSet.<BuildRule>of(),
          ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    }

    public BuildTarget createTarget() {
      return BuildTargetFactory.newInstance(targetName);
    }

    public abstract BuildRule createRule(BuildTarget target);
  }

  // Captures all the common code between the different annotation processing test scenarios.
  private class AnnotationProcessingScenario {
    private final AnnotationProcessingParams.Builder annotationProcessingParamsBuilder;
    private final Map<BuildTarget, BuildRule> buildRuleIndex;
    private final BuildRuleBuilderParams buildRuleBuilderParams;
    private ExecutionContext executionContext;
    private BuildContext buildContext;

    public AnnotationProcessingScenario() {
      annotationProcessingParamsBuilder = new AnnotationProcessingParams.Builder();
      buildRuleIndex = Maps.newHashMap();
      buildRuleBuilderParams = new BuildRuleBuilderParams(buildRuleIndex);
    }

    public AnnotationProcessingParams.Builder getAnnotationProcessingParamsBuilder() {
      return annotationProcessingParamsBuilder;
    }

    public void addAnnotationProcessorTarget(AnnotationProcessorTarget processor) {
      BuildTarget target = processor.createTarget();
      BuildRule rule = processor.createRule(target);

      annotationProcessingParamsBuilder.addProcessorBuildTarget(target);
      buildRuleIndex.put(target, rule);
    }

    public ImmutableList<String> buildAndGetCompileParameters() throws IOException {
      DefaultJavaLibraryRule javaLibrary = createJavaLibraryRule();
      buildContext = createBuildContext(javaLibrary, "");
      List<Step> steps = javaLibrary.buildInternal(buildContext);
      JavacInMemoryStep javacCommand = lastJavacCommand(steps);

      executionContext = ExecutionContext.builder()
          .setVerbosity(Verbosity.SILENT)
          .setProjectFilesystem(new ProjectFilesystem(new File(".")))
          .setConsole(new Console(System.out, System.err, Ansi.withoutTty()))
          .setDebugEnabled(true)
          .build();

      ImmutableList<String> options = javacCommand.getOptions(executionContext,
          /* buildClasspathEntries */ ImmutableSet.<String>of());

      return options;
    }

    // TODO(simons): Actually generate a java library rule, rather than an android one.
    private DefaultJavaLibraryRule createJavaLibraryRule() {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(ANNOTATION_SCENARIO_TARGET);
      annotationProcessingParamsBuilder.setOwnerTarget(buildTarget);

      String src = "android/java/src/com/facebook/Main.java";

      AnnotationProcessingParams params = annotationProcessingParamsBuilder.build(buildRuleBuilderParams);
      JavacOptions.Builder options = JavacOptions.builder().setAnnotationProcessingData(params);

      return new AndroidLibraryRule(
          new BuildRuleParams(
              buildTarget,
              /* deps */ ImmutableSortedSet.<BuildRule>of(),
              /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of()),
          ImmutableSet.of(src),
          /* resources */ ImmutableSet.<String>of(),
          /* proguardConfig */ Optional.<String>absent(),
          options.build(),
          /* manifestFile */ Optional.<String>absent());
    }

    private JavacInMemoryStep lastJavacCommand(Iterable<Step> commands) {
      Step javac = null;
      for (Step step : commands) {
        if (step instanceof JavacInMemoryStep) {
          javac = step;
          // Intentionally no break here, since we want the last one.
        }
      }
      assertNotNull("Expected a JavacInMemoryCommand in command list", javac);
      return (JavacInMemoryStep)javac;
    }
  }
}
