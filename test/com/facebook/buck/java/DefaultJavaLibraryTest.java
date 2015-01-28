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

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.util.BuckConstant.BIN_PATH;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidLibrary;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class DefaultJavaLibraryTest {
  private static final String ANNOTATION_SCENARIO_TARGET =
      "//android/java/src/com/facebook:fb";
  private static final String ANNOTATION_SCENARIO_GEN_PATH_POSTIX =
      BuckConstant.ANNOTATION_DIR + "/android/java/src/com/facebook/__fb_gen__";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();
  private String annotationScenarioGenPath;

  @Before
  public void stubOutBuildContext() {
    StepRunner stepRunner = createNiceMock(StepRunner.class);
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);
    replay(packageFinder, stepRunner);

    annotationScenarioGenPath = new File(
        tmp.getRoot(),
        ANNOTATION_SCENARIO_GEN_PATH_POSTIX).getAbsolutePath();
  }

  /** Make sure that when isAndroidLibrary is true, that the Android bootclasspath is used. */
  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testBuildInternalWithAndroidBootclasspath() throws IOException {
    String folder = "android/java/src/com/facebook";
    tmp.newFolder(folder.split("/"));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//" + folder + ":fb");

    Path src = Paths.get(folder, "Main.java");
    tmp.newFile(src.toString());

    BuildRuleResolver ruleResolver = new BuildRuleResolver(
        ImmutableMap.<BuildTarget, BuildRule>of());
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    BuildRule libraryRule = AndroidLibraryBuilder
        .createBuilder(buildTarget)
        .addSrc(src)
        .build(ruleResolver);
    DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) libraryRule;

    String bootclasspath = "effects.jar:maps.jar:usb.jar:";
    BuildContext context = createBuildContext(libraryRule, bootclasspath, projectFilesystem);

    List<Step> steps = javaLibrary.getBuildSteps(context, new FakeBuildableContext());

    // Find the JavacStep and verify its bootclasspath.
    Step step = Iterables.find(steps, new Predicate<Step>() {
      @Override
      public boolean apply(Step command) {
        return command instanceof JavacStep;
      }
    });
    assertNotNull("Expected a JavacStep in the steplist.", step);
    JavacStep javac = (JavacStep) step;
    assertEquals("Should compile Main.java rather than generated R.java.",
        ImmutableSet.of(src),
        javac.getSrcs());
  }

  @Test
  public void testGetInputsToCompareToOutputWhenAResourceAsSourcePathExists() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget genruleBuildTarget = BuildTargetFactory.newInstance("//generated:stuff");
    BuildRule genrule = GenruleBuilder
        .newGenruleBuilder(genruleBuildTarget)
        .setBash("echo 'aha' > $OUT")
        .setOut("stuff.txt")
        .build(ruleResolver);

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem() {
      @Override
      public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
        return false;
      }
    };

    DefaultJavaLibrary javaRule = (DefaultJavaLibrary) JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//library:code"))
        .addResource(new BuildTargetSourcePath(genrule.getBuildTarget()))
        .addResource(new TestSourcePath("library/data.txt"))
        .build(ruleResolver, filesystem);

    assertEquals(
        "Generated files should not be included in getInputsToCompareToOutput() because they " +
            "should not be part of the RuleKey computation.",
        ImmutableList.of(Paths.get("library/data.txt")),
        javaRule.getInputsToCompareToOutput());
  }

  @Test
  public void testJavaLibaryThrowsIfResourceIsDirectory() {
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem() {
      @Override
      public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
        return true;
      }
    };

    try {
      JavaLibraryBuilder
          .createBuilder(BuildTargetFactory.newInstance("//library:code"))
          .addResource(new TestSourcePath("library"))
          .build(new BuildRuleResolver(), filesystem);
      fail("An exception should have been thrown because a directory was passed as a resource.");
    } catch (HumanReadableException e) {
      assertTrue(e.getHumanReadableErrorMessage().contains("a directory is not a valid input"));
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
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor"));
    assertEquals(
        "Expected '-s " + annotationScenarioGenPath + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(annotationScenarioGenPath));

    for (String parameter : parameters) {
      assertTrue("Expected no custom annotation options.", !parameter.startsWith("-A") ||
          parameter.startsWith("-A" + AbiWriterProtocol.PARAM_ABI_OUTPUT_FILE));
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
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
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
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
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
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  @Test
  public void testGetClasspathEntriesMap() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildRule libraryOne = JavaLibraryBuilder.createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build(ruleResolver);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    BuildRule libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .build(ruleResolver);

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    BuildRule parent = JavaLibraryBuilder
        .createBuilder(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(libraryTwo.getBuildTarget())
        .build(ruleResolver);

    assertEquals(
        ImmutableSetMultimap.of(
            getJavaLibrary(libraryOne),
            Paths.get("buck-out/gen/lib__libone__output/libone.jar"),
            getJavaLibrary(libraryTwo),
            Paths.get("buck-out/gen/lib__libtwo__output/libtwo.jar"),
            getJavaLibrary(parent),
            Paths.get("buck-out/gen/lib__parent__output/parent.jar")),
        ((HasClasspathEntries) parent).getTransitiveClasspathEntries());
  }

  @Test
  public void testClasspathForJavacCommand() throws IOException {
    // libraryOne responds like an ordinary prebuilt_jar with no dependencies. We have to use a
    // FakeJavaLibraryRule so that we can override the behavior of getAbiKey().
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    FakeJavaLibrary libraryOne = new FakeJavaLibrary(
        libraryOneTarget,
        new SourcePathResolver(new BuildRuleResolver())) {
      @Override
      public Sha1HashCode getAbiKey() {
        return ImmutableSha1HashCode.of(Strings.repeat("cafebabe", 5));
      }

      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
        return ImmutableSetMultimap.of(
            (JavaLibrary) this,
            Paths.get("java/src/com/libone/bar.jar"));
      }

      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
        return ImmutableSetMultimap.of(
            (JavaLibrary) this,
            Paths.get("java/src/com/libone/bar.jar"));
      }

      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        return ImmutableSetMultimap.of();
      }
    };

    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(libraryOneTarget, libraryOne);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    BuildRule libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .build(ruleResolver);

    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    expect(buildContext.getBuildDependencies()).andReturn(BuildDependencies.FIRST_ORDER_ONLY)
        .times(2);
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    expect(buildContext.getJavaPackageFinder()).andReturn(javaPackageFinder);

    replay(buildContext, javaPackageFinder);

    List<Step> steps = libraryTwo.getBuildSteps(buildContext, new FakeBuildableContext());

    EasyMock.verify(buildContext, javaPackageFinder);

    ImmutableList<JavacStep> javacSteps = FluentIterable
        .from(steps)
        .filter(JavacStep.class)
        .toList();
    assertEquals("There should be only one javac step.", 1, javacSteps.size());
    JavacStep javacStep = javacSteps.get(0);
    assertEquals(
        "The classpath to use when compiling //:libtwo according to getDeclaredClasspathEntries()" +
            " should contain only bar.jar.",
        ImmutableSet.of(Paths.get("java/src/com/libone/bar.jar")),
        ImmutableSet.copyOf(
            ((JavaLibrary) libraryTwo).getDeclaredClasspathEntries().values()));
    assertEquals(
        "The classpath for the javac step to compile //:libtwo should contain only bar.jar.",
        ImmutableSet.of(Paths.get("java/src/com/libone/bar.jar")),
        javacStep.getClasspathEntries());
  }

  /**
   * Verify adding an annotation processor java binary with options.
   */
  @Test
  public void testAddAnnotationProcessorWithOptions() throws IOException {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(AnnotationProcessorTarget.VALID_JAVA_BINARY);

    scenario.getAnnotationProcessingParamsBuilder().addAllProcessors(
        ImmutableList.of("MyProcessor"));
    scenario.getAnnotationProcessingParamsBuilder().addParameter("MyParameter");
    scenario.getAnnotationProcessingParamsBuilder().addParameter("MyKey=MyValue");
    scenario.getAnnotationProcessingParamsBuilder().setProcessOnly(true);

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
    MoreAsserts.assertContainsOne(parameters, "-proc:only");

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor"));
    assertEquals(
        "Expected '-s " + annotationScenarioGenPath + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(annotationScenarioGenPath));

    MoreAsserts.assertContainsOne(parameters, "-AMyParameter");
    MoreAsserts.assertContainsOne(parameters, "-AMyKey=MyValue");
  }

  private void assertHasProcessor(List<String> params, String processor) {
    int index = params.indexOf("-processor");
    if (index >= params.size()) {
      fail(String.format("No processor argument found in %s.", params));
    }

    Set<String> processors = ImmutableSet.copyOf(Splitter.on(',').split(params.get(index + 1)));
    if (!processors.contains(processor)) {
      fail(String.format("Annotation processor %s not found in %s.", processor, params));
    }
  }

  @Test
  public void testExportedDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget nonIncludedTarget = BuildTargetFactory.newInstance("//:not_included");
    BuildRule notIncluded = JavaLibraryBuilder
        .createBuilder(nonIncludedTarget)
        .addSrc(Paths.get("java/src/com/not_included/Raz.java"))
        .build(ruleResolver);

    BuildTarget includedTarget = BuildTargetFactory.newInstance("//:included");
    BuildRule included = JavaLibraryBuilder
        .createBuilder(includedTarget)
        .addSrc(Paths.get("java/src/com/included/Rofl.java"))
        .build(ruleResolver);

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildRule libraryOne = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addDep(notIncluded.getBuildTarget())
        .addDep(included.getBuildTarget())
        .addExportedDep(included.getBuildTarget())
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build(ruleResolver);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    BuildRule libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .addExportedDep(libraryOne.getBuildTarget())
        .build(ruleResolver);

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    BuildRule parent = JavaLibraryBuilder
        .createBuilder(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(libraryTwo.getBuildTarget())
        .build(ruleResolver);

    assertEquals(
        "A java_library that depends on //:libone should include only libone.jar in its " +
            "classpath when compiling itself.",
        ImmutableSetMultimap.of(
            getJavaLibrary(notIncluded),
            Paths.get("buck-out/gen/lib__not_included__output/not_included.jar")),
        getJavaLibrary(notIncluded).getOutputClasspathEntries());

    assertEquals(
        ImmutableSetMultimap.of(
            getJavaLibrary(included),
            Paths.get("buck-out/gen/lib__included__output/included.jar")),
        getJavaLibrary(included).getOutputClasspathEntries());

    assertEquals(
        ImmutableSetMultimap.of(
            getJavaLibrary(included),
            Paths.get("buck-out/gen/lib__included__output/included.jar"),
            getJavaLibrary(libraryOne),
            Paths.get("buck-out/gen/lib__libone__output/libone.jar"),
            getJavaLibrary(libraryOne),
            Paths.get("buck-out/gen/lib__included__output/included.jar")),
        getJavaLibrary(libraryOne).getOutputClasspathEntries());

    assertEquals(
        "//:libtwo exports its deps, so a java_library that depends on //:libtwo should include " +
            "both libone.jar and libtwo.jar in its classpath when compiling itself.",
        ImmutableSetMultimap.of(
            getJavaLibrary(libraryOne),
            Paths.get("buck-out/gen/lib__libone__output/libone.jar"),
            getJavaLibrary(libraryOne),
            Paths.get("buck-out/gen/lib__included__output/included.jar"),
            getJavaLibrary(libraryTwo),
            Paths.get("buck-out/gen/lib__libone__output/libone.jar"),
            getJavaLibrary(libraryTwo),
            Paths.get("buck-out/gen/lib__libtwo__output/libtwo.jar"),
            getJavaLibrary(libraryTwo),
            Paths.get("buck-out/gen/lib__included__output/included.jar")),
        getJavaLibrary(libraryTwo).getOutputClasspathEntries());

    assertEquals(
        "A java_binary that depends on //:parent should include libone.jar, libtwo.jar and " +
            "parent.jar.",
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .put(
                getJavaLibrary(included),
                Paths.get("buck-out/gen/lib__included__output/included.jar"))
            .put(
                getJavaLibrary(notIncluded),
                Paths.get("buck-out/gen/lib__not_included__output/not_included.jar"))
            .putAll(
                getJavaLibrary(libraryOne),
                Paths.get("buck-out/gen/lib__included__output/included.jar"),
                Paths.get("buck-out/gen/lib__libone__output/libone.jar"))
            .putAll(
                getJavaLibrary(libraryTwo),
                Paths.get("buck-out/gen/lib__included__output/included.jar"),
                Paths.get("buck-out/gen/lib__not_included__output/not_included.jar"),
                Paths.get("buck-out/gen/lib__libone__output/libone.jar"),
                Paths.get("buck-out/gen/lib__libtwo__output/libtwo.jar"))
            .put(getJavaLibrary(parent), Paths.get("buck-out/gen/lib__parent__output/parent.jar"))
            .build(),
        getJavaLibrary(parent).getTransitiveClasspathEntries());

    assertEquals(
        "A java_library that depends on //:parent should include only parent.jar in its " +
            "-classpath when compiling itself.",
        ImmutableSetMultimap.of(
            getJavaLibrary(parent),
            Paths.get("buck-out/gen/lib__parent__output/parent.jar")),
        getJavaLibrary(parent).getOutputClasspathEntries());
  }

  /**
   * Tests that an error is thrown when non-java library rules are listed in the exported deps
   * parameter.
   */
  @Test
  public void testExportedDepsShouldOnlyContainJavaLibraryRules() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget genruleBuildTarget = BuildTargetFactory.newInstance("//generated:stuff");
    BuildRule genrule = GenruleBuilder
        .newGenruleBuilder(genruleBuildTarget)
        .setBash("echo 'aha' > $OUT")
        .setOut("stuff.txt")
        .build(ruleResolver);

    String commonLibAbiKeyHash = Strings.repeat("a", 40);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    try {
      createDefaultJavaLibaryRuleWithAbiKey(
          commonLibAbiKeyHash,
          buildTarget,
          // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
          /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
          /* deps */ ImmutableSortedSet.<BuildRule>of(),
          /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(genrule));
      fail("A non-java library listed as exported dep should have thrown.");
    } catch (HumanReadableException e) {
      String expected =
          buildTarget + ": exported dep " +
          genruleBuildTarget + " (" + genrule.getType() + ") " +
          "must be a type of java library.";
      assertEquals(expected, e.getMessage());
    }

  }

  /**
   * Tests DefaultJavaLibraryRule#getAbiKeyForDeps() when the dependencies contain a JavaAbiRule
   * that is not a JavaLibraryRule.
   */
  @Test
  public void testGetAbiKeyForDepsWithMixedDeps() throws IOException {
    String tinyLibAbiKeyHash = Strings.repeat("a", 40);
    BuildRule tinyLibrary = createDefaultJavaLibaryRuleWithAbiKey(
        tinyLibAbiKeyHash,
        BuildTargetFactory.newInstance("//:tinylib"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    String javaAbiRuleKeyHash = Strings.repeat("b", 40);
    FakeJavaAbiRule fakeJavaAbiRule = new FakeJavaAbiRule(
        AndroidResourceDescription.TYPE,
        BuildTargetFactory.newInstance("//:tinylibfakejavaabi"),
        javaAbiRuleKeyHash);

    BuildRule defaultJavaLibary = createDefaultJavaLibaryRuleWithAbiKey(
        Strings.repeat("c", 40),
        BuildTargetFactory.newInstance("//:javalib"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSortedSet.<BuildRule>of(tinyLibrary, fakeJavaAbiRule),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putUnencodedChars(tinyLibAbiKeyHash);
    hasher.putUnencodedChars(javaAbiRuleKeyHash);

    assertEquals(
        ImmutableSha1HashCode.of(hasher.hash().toString()),
        ((AbiRule) defaultJavaLibary).getAbiKeyForDeps());
  }

  /**
   * @see com.facebook.buck.rules.AbiRule#getAbiKeyForDeps()
   */
  @Test
  public void testGetAbiKeyForDepsInThePresenceOfExportedDeps() throws IOException {
    // Create a java_library named //:tinylib with a hardcoded ABI key.
    String tinyLibAbiKeyHash = Strings.repeat("a", 40);
    BuildRule tinyLibrary = createDefaultJavaLibaryRuleWithAbiKey(
        tinyLibAbiKeyHash,
        BuildTargetFactory.newInstance("//:tinylib"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Create two java_library rules, each of which depends on //:tinylib, but only one of which
    // exports its deps.
    String commonWithExportAbiKeyHash = Strings.repeat("b", 40);
    BuildRule commonWithExport = createDefaultJavaLibaryRuleWithAbiKey(
        commonWithExportAbiKeyHash,
        BuildTargetFactory.newInstance("//:common_with_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSortedSet.<BuildRule>of(tinyLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(tinyLibrary));
    BuildRule commonNoExport = createDefaultJavaLibaryRuleWithAbiKey(
        /* abiHash */ null,
        BuildTargetFactory.newInstance("//:common_no_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSortedSet.<BuildRule>of(tinyLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Verify getAbiKeyForDeps() for the two //:common_XXX rules.
    assertEquals(
        "getAbiKeyForDeps() should be the same for both rules because they have the same deps.",
        ((AbiRule) commonNoExport).getAbiKeyForDeps(),
        ((AbiRule) commonWithExport).getAbiKeyForDeps());
    String expectedAbiKeyForDepsHash = Hashing.sha1().newHasher()
        .putUnencodedChars(tinyLibAbiKeyHash).hash().toString();
    String observedAbiKeyForDepsHash =
        ((AbiRule) commonNoExport).getAbiKeyForDeps().getHash();
    assertEquals(expectedAbiKeyForDepsHash, observedAbiKeyForDepsHash);

    // Create a BuildRuleResolver populated with the three build rules we created thus far.
    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(tinyLibrary.getBuildTarget(), tinyLibrary);
    buildRuleIndex.put(commonWithExport.getBuildTarget(), commonWithExport);
    buildRuleIndex.put(commonNoExport.getBuildTarget(), commonNoExport);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    // Create two rules, each of which depends on one of the //:common_XXX rules.
    BuildRule consumerNoExport = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:consumer_no_export"))
        .addDep(commonNoExport.getBuildTarget())
        .build(ruleResolver);
    BuildRule consumerWithExport = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:consumer_with_export"))
        .addDep(commonWithExport.getBuildTarget())
        .build(ruleResolver);

    // Verify getAbiKeyForDeps() for the two //:consumer_XXX rules.

    // This differs from the EMPTY_ABI_KEY in that the value of that comes from the SHA1 of an empty
    // jar file, whereas this is constructed from the empty set of values.
    Sha1HashCode noAbiDeps = ImmutableSha1HashCode.of(Hashing.sha1().newHasher().hash().toString());
    assertEquals(
        "The ABI of the deps of //:consumer_no_export should be the empty ABI.",
        noAbiDeps,
        ((AbiRule) consumerNoExport).getAbiKeyForDeps());
    assertThat(
        "Although //:consumer_no_export and //:consumer_with_export have the same deps, " +
        "the ABIs of their deps will differ because of the use of exported_deps is non-empty",
        ((AbiRule) consumerNoExport).getAbiKeyForDeps(),
        not(equalTo(((AbiRule) consumerWithExport).getAbiKeyForDeps())));
    String expectedAbiKeyNoDepsHashForConsumerWithExport = Hashing.sha1().newHasher()
        .putUnencodedChars(((HasJavaAbi) commonWithExport).getAbiKey().getHash())
        .putUnencodedChars(tinyLibAbiKeyHash)
        .hash()
        .toString();
    String observedAbiKeyNoDepsHashForConsumerWithExport =
        ((AbiRule) consumerWithExport).getAbiKeyForDeps()
        .getHash();
    assertEquals(
        "By hardcoding the ABI keys for the deps, we made getAbiKeyForDeps() a predictable value.",
        expectedAbiKeyNoDepsHashForConsumerWithExport,
        observedAbiKeyNoDepsHashForConsumerWithExport);
  }

  /**
   * @see DefaultJavaLibrary#getAbiKey()
   */
  @Test
  public void testGetAbiKeyInThePresenceOfExportedDeps() throws IOException {
    // Create a java_library named //:commonlib with a hardcoded ABI key.
    String commonLibAbiKeyHash = Strings.repeat("a", 40);
    BuildRule commonLibrary = createDefaultJavaLibaryRuleWithAbiKey(
        commonLibAbiKeyHash,
        BuildTargetFactory.newInstance("//:commonlib"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Create two java_library rules, each of which depends on //:commonlib, but only one of which
    // exports its deps.
    String libWithExportAbiKeyHash = Strings.repeat("b", 40);
    BuildRule libWithExport = createDefaultJavaLibaryRuleWithAbiKey(
        libWithExportAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_with_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSortedSet.<BuildRule>of(commonLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(commonLibrary));
    String libNoExportAbiKeyHash = Strings.repeat("c", 40);
    BuildRule libNoExport = createDefaultJavaLibaryRuleWithAbiKey(
        /* abiHash */ libNoExportAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_no_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSortedSet.<BuildRule>of(commonLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Verify getAbiKey() for the two //:lib_XXX rules.
    String expectedLibWithExportAbiKeyHash = Hashing.sha1().newHasher()
        .putUnencodedChars(commonLibAbiKeyHash)
        .putUnencodedChars(libWithExportAbiKeyHash)
        .hash()
        .toString();
    assertEquals(
        "getAbiKey() should include the dependencies' ABI keys for the rule with export_deps=true.",
        expectedLibWithExportAbiKeyHash,
        ((HasJavaAbi) libWithExport).getAbiKey().getHash());

    assertEquals(
        "getAbiKey() should not include the dependencies' ABI keys for the rule with " +
            "export_deps=false.",
        libNoExportAbiKeyHash,
        ((HasJavaAbi) libNoExport).getAbiKey().getHash());
  }

  /**
   * @see DefaultJavaLibrary#getAbiKey()
   */
  @Test
  public void testGetAbiKeyRecursiveExportedDeps() throws IOException {
    String libAAbiKeyHash = Strings.repeat("a", 40);
    String libBAbiKeyHash = Strings.repeat("b", 40);
    String libCAbiKeyHash = Strings.repeat("c", 40);
    String libDAbiKeyHash = Strings.repeat("d", 40);

    // Test the following dependency graph:
    // a(export_deps=true) -> b(export_deps=true) -> c(export_deps=true) -> d(export_deps=true)
    BuildRule libD = createDefaultJavaLibaryRuleWithAbiKey(
        libDAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_d"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* exporedtDeps */ ImmutableSortedSet.<BuildRule>of());
    BuildRule libC = createDefaultJavaLibaryRuleWithAbiKey(
        libCAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_c"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(libD),
        /* exporedtDeps */ ImmutableSortedSet.<BuildRule>of(libD));
    BuildRule libB = createDefaultJavaLibaryRuleWithAbiKey(
        libBAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_b"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(libC),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(libC));
    BuildRule libA = createDefaultJavaLibaryRuleWithAbiKey(
        libAAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_a"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(libB),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(libB));

    assertEquals(
        "If a rule has no dependencies its final ABI key should be the rule's own ABI key.",
        libDAbiKeyHash,
        ((HasJavaAbi) libD).getAbiKey().getHash());

    String expectedLibCAbiKeyHash = Hashing.sha1().newHasher()
        .putUnencodedChars(libDAbiKeyHash)
        .putUnencodedChars(libCAbiKeyHash)
        .hash()
        .toString();
    assertEquals(
        "The ABI key for lib_c should contain lib_d's ABI key.",
        expectedLibCAbiKeyHash,
        ((HasJavaAbi) libC).getAbiKey().getHash());

    String expectedLibBAbiKeyHash = Hashing.sha1().newHasher()
        .putUnencodedChars(expectedLibCAbiKeyHash)
        .putUnencodedChars(libDAbiKeyHash)
        .putUnencodedChars(libBAbiKeyHash)
        .hash()
        .toString();
    assertEquals(
        "The ABI key for lib_b should contain lib_c's and lib_d's ABI keys.",
        expectedLibBAbiKeyHash,
        ((HasJavaAbi) libB).getAbiKey().getHash());

    String expectedLibAAbiKeyHash = Hashing.sha1().newHasher()
        .putUnencodedChars(expectedLibBAbiKeyHash)
        .putUnencodedChars(expectedLibCAbiKeyHash)
        .putUnencodedChars(libAAbiKeyHash)
        .hash()
        .toString();
    assertEquals(
        "The ABI key for lib_a should contain lib_b's, lib_c's and lib_d's ABI keys.",
        expectedLibAAbiKeyHash,
        ((HasJavaAbi) libA).getAbiKey().getHash());

    // Test the following dependency graph:
    // a(export_deps=true) -> b(export_deps=true) -> c(export_deps=false) -> d(export_deps=false)
    libD = createDefaultJavaLibaryRuleWithAbiKey(
        libDAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_d2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());
    libC = createDefaultJavaLibaryRuleWithAbiKey(
        libCAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_c2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(libD),
        /* exportDeps */ ImmutableSortedSet.<BuildRule>of());
    libB = createDefaultJavaLibaryRuleWithAbiKey(
        libBAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_b2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(libC),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(libC));
    libA = createDefaultJavaLibaryRuleWithAbiKey(
        libAAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_a2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(libB),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(libB));

    assertEquals(
        "If export_deps is false, the final ABI key should be the rule's own ABI key.",
        libDAbiKeyHash,
        ((HasJavaAbi) libD).getAbiKey().getHash());

    assertEquals(
        "If export_deps is false, the final ABI key should be the rule's own ABI key.",
        libCAbiKeyHash,
        ((HasJavaAbi) libC).getAbiKey().getHash());

    expectedLibBAbiKeyHash = Hashing.sha1().newHasher()
        .putUnencodedChars(libCAbiKeyHash)
        .putUnencodedChars(libBAbiKeyHash)
        .hash()
        .toString();
    assertEquals(
        "The ABI key for lib_b should contain lib_c's ABI key.",
        expectedLibBAbiKeyHash,
        ((HasJavaAbi) libB).getAbiKey().getHash());

    expectedLibAAbiKeyHash = Hashing.sha1().newHasher()
        .putUnencodedChars(expectedLibBAbiKeyHash)
        .putUnencodedChars(libCAbiKeyHash)
        .putUnencodedChars(libAAbiKeyHash)
        .hash()
        .toString();
    assertEquals(
        "The ABI key for lib_a should contain lib_b's, lib_c's and lib_d's ABI keys.",
        expectedLibAAbiKeyHash,
        ((HasJavaAbi) libA).getAbiKey().getHash());

  }

  private static BuildRule createDefaultJavaLibaryRuleWithAbiKey(
      @Nullable final String partialAbiHash,
      BuildTarget buildTarget,
      ImmutableSet<String> srcs,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<BuildRule> exportedDeps) {
    ImmutableSortedSet<SourcePath> srcsAsPaths = FluentIterable.from(srcs)
        .transform(MorePaths.TO_PATH)
        .transform(SourcePaths.TO_SOURCE_PATH)
        .toSortedSet(Ordering.natural());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.copyOf(deps))
        .setType(JavaLibraryDescription.TYPE)
        .build();

    return new DefaultJavaLibrary(
        buildRuleParams,
        new SourcePathResolver(new BuildRuleResolver()),
        srcsAsPaths,
        /* resources */ ImmutableSet.<SourcePath>of(),
        /* proguardConfig */ Optional.<Path>absent(),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        exportedDeps,
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        DEFAULT_JAVAC_OPTIONS,
        /* resourcesRoot */ Optional.<Path>absent()) {
      @Override
      public Sha1HashCode getAbiKey() {
        if (partialAbiHash == null) {
          return super.getAbiKey();
        } else {
          return createTotalAbiKey(ImmutableSha1HashCode.of(partialAbiHash));
        }
      }
    };
  }

  @Test
  public void testEmptySuggestBuildFunction() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    JavaLibrary libraryOne = (JavaLibrary) JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/bar.java"))
        .build(ruleResolver);

    BuildContext context = createSuggestContext(ruleResolver,
        BuildDependencies.FIRST_ORDER_ONLY);

    ImmutableSetMultimap<JavaLibrary, Path> classpathEntries =
        libraryOne.getTransitiveClasspathEntries();

    assertEquals(
        Optional.<JavacStep.SuggestBuildRules>absent(),
        ((DefaultJavaLibrary) libraryOne).createSuggestBuildFunction(
            context,
            classpathEntries,
            classpathEntries,
            createJarResolver(/* classToSymbols */ImmutableMap.<Path, String>of())));

    EasyMock.verify(context);
  }

  @Test
  public void testSuggsetDepsReverseTopoSortRespected() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot().toPath());

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildRule libraryOne = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build(ruleResolver);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    BuildRule libraryTwo = JavaLibraryBuilder
        .createBuilder(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOne.getBuildTarget())
        .build(ruleResolver);

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    BuildRule parent = JavaLibraryBuilder
        .createBuilder(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(libraryTwo.getBuildTarget())
        .build(ruleResolver);

    BuildTarget grandparentTarget = BuildTargetFactory.newInstance("//:grandparent");
    BuildRule grandparent = JavaLibraryBuilder
        .createBuilder(grandparentTarget)
        .addSrc(Paths.get("java/src/com/parent/OldManRiver.java"))
        .addDep(parent.getBuildTarget())
        .build(ruleResolver);

    BuildContext context = createSuggestContext(ruleResolver,
        BuildDependencies.WARN_ON_TRANSITIVE);

    ImmutableSetMultimap<JavaLibrary, Path> transitive =
        ((HasClasspathEntries) parent).getTransitiveClasspathEntries();

    ImmutableMap<Path, String> classToSymbols = ImmutableMap.of(
        Iterables.getFirst(transitive.get((JavaLibrary) parent), null),
        "com.facebook.Foo",
        Iterables.getFirst(transitive.get((JavaLibrary) libraryOne), null),
        "com.facebook.Bar",
        Iterables.getFirst(transitive.get((JavaLibrary) libraryTwo), null),
        "com.facebook.Foo");

    Optional<JavacStep.SuggestBuildRules> suggestFn =
        ((DefaultJavaLibrary) grandparent).createSuggestBuildFunction(
            context,
            transitive,
            /* declaredClasspathEntries */ ImmutableSetMultimap.<JavaLibrary, Path>of(),
            createJarResolver(classToSymbols));

    assertTrue(suggestFn.isPresent());
    assertEquals(ImmutableSet.of("//:parent", "//:libone"),
                 suggestFn.get().suggest(
                     projectFilesystem,
                     ImmutableSet.of("com.facebook.Foo", "com.facebook.Bar")));

    EasyMock.verify(context);
  }

  @Test
  public void testRuleKeyIsOrderInsensitiveForSourcesAndResources() throws IOException {
    // Note that these filenames were deliberately chosen to have identical hashes to maximize
    // the chance of order-sensitivity when being inserted into a HashMap.  Just using
    // {foo,bar}.{java,txt} resulted in a passing test even for the old broken code.

    ProjectFilesystem filesystem = new AllExistingProjectFilesystem() {
      @Override
      public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
        return false;
      }
    };
    BuildRuleResolver resolver1 = new BuildRuleResolver();
    SourcePathResolver pathResolver1 = new SourcePathResolver(resolver1);
    DefaultJavaLibrary rule1 = (DefaultJavaLibrary) JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addResource(new TestSourcePath("becgkaifhjd.txt"))
        .addResource(new TestSourcePath("bkhajdifcge.txt"))
        .addResource(new TestSourcePath("cabfghjekid.txt"))
        .addResource(new TestSourcePath("chkdbafijge.txt"))
        .build(resolver1, filesystem);

    BuildRuleResolver resolver2 = new BuildRuleResolver();
    SourcePathResolver pathResolver2 = new SourcePathResolver(resolver2);
    DefaultJavaLibrary rule2 = (DefaultJavaLibrary) JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addResource(new TestSourcePath("chkdbafijge.txt"))
        .addResource(new TestSourcePath("cabfghjekid.txt"))
        .addResource(new TestSourcePath("bkhajdifcge.txt"))
        .addResource(new TestSourcePath("becgkaifhjd.txt"))
        .build(resolver2, filesystem);

    Iterable<Path> inputs1 = rule1.getInputsToCompareToOutput();
    Iterable<Path> inputs2 = rule2.getInputsToCompareToOutput();
    assertEquals(ImmutableList.copyOf(inputs1), ImmutableList.copyOf(inputs2));

    ImmutableMap.Builder<String, String> fileHashes = ImmutableMap.builder();
    for (String filename : ImmutableList.of(
        "agifhbkjdec.java", "bdeafhkgcji.java", "bdehgaifjkc.java", "cfiabkjehgd.java",
        "becgkaifhjd.txt", "bkhajdifcge.txt", "cabfghjekid.txt", "chkdbafijge.txt")) {
      fileHashes.put(filename, Hashing.sha1().hashString(filename, Charsets.UTF_8).toString());
    }
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(FakeFileHashCache.createFromStrings(fileHashes.build()));

    RuleKey.Builder builder1 = ruleKeyBuilderFactory.newInstance(rule1, pathResolver1);
    RuleKey.Builder builder2 = ruleKeyBuilderFactory.newInstance(rule2, pathResolver2);
    rule1.appendToRuleKey(builder1);
    rule2.appendToRuleKey(builder2);
    RuleKey.Builder.RuleKeyPair pair1 = builder1.build();
    RuleKey.Builder.RuleKeyPair pair2 = builder2.build();
    assertEquals(pair1.getTotalRuleKey(), pair2.getTotalRuleKey());
    assertEquals(pair1.getRuleKeyWithoutDeps(), pair2.getRuleKeyWithoutDeps());
  }

  @Test
  public void testWhenNoJavacIsProvidedAJavacInMemoryStepIsAdded() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildRule rule = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .build(ruleResolver);
    DefaultJavaLibrary buildable = (DefaultJavaLibrary) rule;

    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    buildable.createCommandsForJavac(
        buildable.getPathToOutputFile(),
        ImmutableSet.copyOf(buildable.getTransitiveClasspathEntries().values()),
        ImmutableSet.copyOf(buildable.getDeclaredClasspathEntries().values()),
        DEFAULT_JAVAC_OPTIONS,
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacStep.SuggestBuildRules>absent(),
        stepsBuilder,
        libraryOneTarget);

    List<Step> steps = stepsBuilder.build();
    assertEquals(steps.size(), 3);
    assertTrue(((JavacStep) steps.get(2)).getJavac() instanceof Jsr199Javac);
  }

  @Test
  public void testWhenJavacJarIsProvidedAJavacInMemoryStepIsAdded() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    Path javacJarPath = Paths.get("java/src/com/libone/JavacJar.jar");
    BuildRule rule = JavaLibraryBuilder
        .createBuilder(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .setJavacJar(javacJarPath)
        .build(ruleResolver);
    DefaultJavaLibrary buildable = (DefaultJavaLibrary) rule;

    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    buildable.createCommandsForJavac(
        buildable.getPathToOutputFile(),
        ImmutableSet.copyOf(buildable.getTransitiveClasspathEntries().values()),
        ImmutableSet.copyOf(buildable.getDeclaredClasspathEntries().values()),
        buildable.getJavacOptions(),
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacStep.SuggestBuildRules>absent(),
        stepsBuilder,
        libraryOneTarget);

    List<Step> steps = stepsBuilder.build();
    assertEquals(steps.size(), 3);
    assertTrue(((JavacStep) steps.get(2)).getJavac() instanceof Jsr199Javac);
    Jsr199Javac jsrJavac = ((Jsr199Javac) (((JavacStep) steps.get(2)).getJavac()));
    assertTrue(jsrJavac.getJavacJar().isPresent());
    assertEquals(jsrJavac.getJavacJar().get(), javacJarPath);
  }

  @Test
  public void testAddPostprocessClassesCommands() {
    ImmutableList<String> postprocessClassesCommands = ImmutableList.of("tool arg1", "tool2");
    Path outputDirectory = BIN_PATH.resolve("android/java/lib__java__classes");
    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    DefaultJavaLibrary.addPostprocessClassesCommands(
        commands,
        postprocessClassesCommands,
        outputDirectory);

    ImmutableList<Step> steps = commands.build();
    assertEquals(2, steps.size());

    assertTrue(steps.get(0) instanceof ShellStep);
    ShellStep step0 = (ShellStep) steps.get(0);
    assertEquals(
        ImmutableList.of("bash", "-c", "tool arg1 " + outputDirectory),
        step0.getShellCommand(executionContext));

    assertTrue(steps.get(1) instanceof ShellStep);
    ShellStep step1 = (ShellStep) steps.get(1);
    assertEquals(
        ImmutableList.of("bash", "-c", "tool2 " + outputDirectory),
        step1.getShellCommand(executionContext));
  }

  // Utilities
  private JavaLibrary getJavaLibrary(BuildRule rule) {
    return (JavaLibrary) rule;
  }

  private DefaultJavaLibrary.JarResolver createJarResolver(
      final ImmutableMap<Path, String> classToSymbols) {

    ImmutableSetMultimap.Builder<Path, String> resolveMapBuilder =
        ImmutableSetMultimap.builder();

    for (Map.Entry<Path, String> entry : classToSymbols.entrySet()) {
      String fullyQualified = entry.getValue();
      String packageName = fullyQualified.substring(0, fullyQualified.lastIndexOf('.'));
      String className = fullyQualified.substring(fullyQualified.lastIndexOf('.'));
      resolveMapBuilder.putAll(entry.getKey(), fullyQualified, packageName, className);
    }

    final ImmutableSetMultimap<Path, String> resolveMap = resolveMapBuilder.build();

    return new DefaultJavaLibrary.JarResolver() {
      @Override
      public ImmutableSet<String> resolve(ProjectFilesystem filesystem, Path relativeClassPath) {
        if (resolveMap.containsKey(relativeClassPath)) {
          return resolveMap.get(relativeClassPath);
        } else {
          return ImmutableSet.of();
        }
      }
    };
  }

  private BuildContext createSuggestContext(BuildRuleResolver ruleResolver,
                                            BuildDependencies buildDependencies) {
    ActionGraph graph = RuleMap.createGraphFromBuildRules(ruleResolver);

    BuildContext context = EasyMock.createMock(BuildContext.class);
    expect(context.getActionGraph()).andReturn(graph).anyTimes();

    expect(context.getBuildDependencies()).andReturn(buildDependencies).anyTimes();

    replay(context);

    return context;
  }

  // TODO(mbolin): Eliminate the bootclasspath parameter, as it is completely misused in this test.
  private BuildContext createBuildContext(BuildRule javaLibrary,
                                          @Nullable String bootclasspath,
                                          @Nullable ProjectFilesystem projectFilesystem) {
    AndroidPlatformTarget platformTarget = EasyMock.createMock(AndroidPlatformTarget.class);
    ImmutableList<Path> bootclasspathEntries = (bootclasspath == null)
        ? ImmutableList.<Path>of(Paths.get("I am not used"))
        : ImmutableList.of(Paths.get(bootclasspath));
    expect(platformTarget.getBootclasspathEntries()).andReturn(bootclasspathEntries)
        .anyTimes();
    replay(platformTarget);

    if (projectFilesystem == null) {
      projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    }

    // TODO(mbolin): Create a utility that populates a BuildContext.Builder with fakes.
    return ImmutableBuildContext.builder()
        .setActionGraph(RuleMap.createGraphFromSingleRule(javaLibrary))
        .setStepRunner(EasyMock.createMock(StepRunner.class))
        .setProjectFilesystem(projectFilesystem)
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setArtifactCache(new NoopArtifactCache())
        .setBuildDependencies(BuildDependencies.TRANSITIVE)
        .setJavaPackageFinder(EasyMock.createMock(JavaPackageFinder.class))
        .setAndroidBootclasspathSupplier(
            BuildContext.getAndroidBootclasspathSupplierForAndroidPlatformTarget(
                Optional.of(platformTarget)))
        .setEventBus(BuckEventBusFactory.newInstance())
        .build();
  }

  private enum AnnotationProcessorTarget {
    VALID_PREBUILT_JAR("//tools/java/src/com/facebook/library:prebuilt-processors") {
      @Override
      public BuildRule createRule(BuildTarget target) {
        return PrebuiltJarBuilder.createBuilder(target)
            .setBinaryJar(Paths.get("MyJar"))
            .build(new BuildRuleResolver());
      }
    },
    VALID_JAVA_BINARY("//tools/java/src/com/facebook/annotations:custom-processors") {
      @Override
      public BuildRule createRule(BuildTarget target) {
       return new JavaBinaryRuleBuilder(target)
            .setMainClass("com.facebook.Main")
            .build(new BuildRuleResolver());
      }
    },
    VALID_JAVA_LIBRARY("//tools/java/src/com/facebook/somejava:library") {
      @Override
      public BuildRule createRule(BuildTarget target) {
        return JavaLibraryBuilder.createBuilder(target)
            .addSrc(Paths.get("MyClass.java"))
            .setProguardConfig(Paths.get("MyProguardConfig"))
            .build(new BuildRuleResolver());
      }
    };

    private final String targetName;

    private AnnotationProcessorTarget(String targetName) {
      this.targetName = targetName;
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
    private ExecutionContext executionContext;
    private BuildContext buildContext;

    public AnnotationProcessingScenario() throws IOException {
      annotationProcessingParamsBuilder = new AnnotationProcessingParams.Builder();
      buildRuleIndex = Maps.newHashMap();
    }

    public AnnotationProcessingParams.Builder getAnnotationProcessingParamsBuilder() {
      return annotationProcessingParamsBuilder;
    }

    public void addAnnotationProcessorTarget(AnnotationProcessorTarget processor) {
      BuildTarget target = processor.createTarget();
      BuildRule rule = processor.createRule(target);

      annotationProcessingParamsBuilder.addProcessorBuildTarget(rule);
      buildRuleIndex.put(target, rule);
    }

    public ImmutableList<String> buildAndGetCompileParameters() throws IOException {
      ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot().toPath());
      BuildRule javaLibrary = createJavaLibraryRule(projectFilesystem);
      buildContext = createBuildContext(javaLibrary, /* bootclasspath */ null, projectFilesystem);
      List<Step> steps = javaLibrary.getBuildSteps(
          buildContext, new FakeBuildableContext());
      JavacStep javacCommand = lastJavacCommand(steps);

      executionContext = TestExecutionContext.newBuilder()
          .setProjectFilesystem(projectFilesystem)
          .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
          .setDebugEnabled(true)
          .build();

      ImmutableList<String> options = javacCommand.getOptions(executionContext,
          /* buildClasspathEntries */ ImmutableSet.<Path>of());

      return options;
    }

    // TODO(simons): Actually generate a java library rule, rather than an android one.
    private BuildRule createJavaLibraryRule(ProjectFilesystem projectFilesystem)
        throws IOException {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(ANNOTATION_SCENARIO_TARGET);
      annotationProcessingParamsBuilder.setOwnerTarget(buildTarget);
      annotationProcessingParamsBuilder.setProjectFilesystem(projectFilesystem);

      tmp.newFolder("android", "java", "src", "com", "facebook");
      String src = "android/java/src/com/facebook/Main.java";
      tmp.newFile(src);

      AnnotationProcessingParams params = annotationProcessingParamsBuilder.build();
      ImmutableJavacOptions.Builder options = JavacOptions.builder(DEFAULT_JAVAC_OPTIONS)
          .setAnnotationProcessingParams(params);

      BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
          .setProjectFilesystem(projectFilesystem)
          .setType(AndroidLibraryDescription.TYPE)
          .build();

      return new AndroidLibrary(
          buildRuleParams,
          new SourcePathResolver(new BuildRuleResolver()),
          ImmutableSet.of(new TestSourcePath(src)),
          /* resources */ ImmutableSet.<SourcePath>of(),
          /* proguardConfig */ Optional.<Path>absent(),
          /* postprocessClassesCommands */ ImmutableList.<String>of(),
          /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
          /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
          /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
          options.build(),
          /* resourcesRoot */ Optional.<Path>absent(),
          /* manifestFile */ Optional.<SourcePath>absent(),
          /* isPrebuiltAar */ false);
    }

    private JavacStep lastJavacCommand(Iterable<Step> commands) {
      Step javac = null;
      for (Step step : commands) {
        if (step instanceof JavacStep) {
          javac = step;
          // Intentionally no break here, since we want the last one.
        }
      }
      assertNotNull("Expected a JavacStep in step list", javac);
      return (JavacStep) javac;
    }
  }

  private static class FakeJavaAbiRule extends FakeBuildRule implements HasJavaAbi {
    private final String abiKeyHash;

    public FakeJavaAbiRule(BuildRuleType type, BuildTarget buildTarget, String abiKeyHash) {
      super(type, buildTarget, new SourcePathResolver(new BuildRuleResolver()));
      this.abiKeyHash = abiKeyHash;
    }

    @Override
    public Sha1HashCode getAbiKey() {
      return ImmutableSha1HashCode.of(abiKeyHash);
    }
  }
}
