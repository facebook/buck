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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.util.BuckConstant.SCRATCH_PATH;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidLibrary;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.abi.AbiWriterProtocol;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyBuilderFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
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

    String bootclasspath = "effects.jar" + File.pathSeparator + "maps.jar" +
        File.pathSeparator + "usb.jar" + File.pathSeparator;
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
          .addResource(new FakeSourcePath("library"))
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
  public void testGetClasspathDeps() {
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

    assertThat(
        ((HasClasspathEntries) parent).getTransitiveClasspathDeps(),
        Matchers.equalTo(
            ImmutableSet.of(
                getJavaLibrary(libraryOne),
                getJavaLibrary(libraryTwo),
                getJavaLibrary(parent))));
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
      public Optional<SourcePath> getAbiJar() {
        return Optional.absent();
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

      @Override
      public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
        return ImmutableSet.of();
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

    assertThat(
        getJavaLibrary(parent).getTransitiveClasspathDeps(),
        Matchers.equalTo(
            ImmutableSet.<JavaLibrary>builder()
                .add(getJavaLibrary(included))
                .add(getJavaLibrary(notIncluded))
                .add(getJavaLibrary(libraryOne))
                .add(getJavaLibrary(libraryTwo))
                .add(getJavaLibrary(parent))
                .build()));

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

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    try {
      createDefaultJavaLibraryRuleWithAbiKey(
          buildTarget,
          // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
          /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
          /* deps */ ImmutableSortedSet.<BuildRule>of(),
          /* exportedDeps */ ImmutableSortedSet.of(genrule));
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
   * Tests that input-based rule keys work properly with generated sources.
   */
  @Test
  public void testInputBasedRuleKeySourceChange() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library consuming a source generated by a genrule and grab its rule key.
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something")
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("class Test {}", genSrc.getPathToOutput());
    JavaLibrary library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(new BuildTargetSourcePath(genSrc.getBuildTarget()))
            .build(resolver, filesystem);
    InputBasedRuleKeyBuilderFactory factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey originalRuleKey = factory.build(library);

    // Now change the genrule such that its rule key changes, but it's output stays the same (since
    // we don't change it).  This should *not* affect the input-based rule key of the consuming
    // java library, since it only cares about the contents of the source.
    resolver = new BuildRuleResolver();
    genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something else")
            .build(resolver, filesystem);
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(new BuildTargetSourcePath(genSrc.getBuildTarget()))
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.equalTo(unaffectedRuleKey));

    // Now actually modify the source, which should make the input-based rule key change.
    resolver = new BuildRuleResolver();
    genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something else")
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("class Test2 {}", genSrc.getPathToOutput());
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(new BuildTargetSourcePath(genSrc.getBuildTarget()))
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(Matchers.equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with simple Java library deps.
   */
  @Test
  public void testInputBasedRuleKeyWithJavaLibraryDep() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep.
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    JavaLibrary dep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("Source.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("JAR contents", dep.getPathToOutput());
    filesystem.writeContentsToPath(
        "ABI JAR contents",
        pathResolver.deprecatedGetPath(dep.getAbiJar().get()));
    JavaLibrary library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep.getBuildTarget())
            .build(resolver, filesystem);
    InputBasedRuleKeyBuilderFactory factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey originalRuleKey = factory.build(library);

    // Now change the Java library dependency such that its rule key changes, and change its JAR
    // contents, but keep its ABI JAR the same.  This should *not* affect the input-based rule key
    // of the consuming java library, since it only cares about the contents of the ABI JAR.
    resolver = new BuildRuleResolver();
    dep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("Source.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("different JAR contents", dep.getPathToOutput());
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep.getBuildTarget())
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.equalTo(unaffectedRuleKey));

    // Now actually change the Java library dependency's ABI JAR.  This *should* affect the
    // input-based rule key of the consuming java library.
    resolver = new BuildRuleResolver();
    pathResolver = new SourcePathResolver(resolver);
    dep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("Source.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath(
        "changed ABI JAR contents",
        pathResolver.deprecatedGetPath(dep.getAbiJar().get()));
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep.getBuildTarget())
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(Matchers.equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with a Java library dep exported by a
   * first-order dep.
   */
  @Test
  public void testInputBasedRuleKeyWithExportedDeps() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep exporting another Java
    // library dep.
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    JavaLibrary exportedDep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"))
            .addSrc(Paths.get("Source1.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("JAR contents", exportedDep.getPathToOutput());
    filesystem.writeContentsToPath(
        "ABI JAR contents",
        pathResolver.deprecatedGetPath(exportedDep.getAbiJar().get()));
    JavaLibrary dep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addExportedDep(exportedDep.getBuildTarget())
            .build(resolver, filesystem);
    JavaLibrary library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep.getBuildTarget())
            .build(resolver, filesystem);
    InputBasedRuleKeyBuilderFactory factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey originalRuleKey = factory.build(library);

    // Now change the exported Java library dependency such that its rule key changes, and change
    // its JAR contents, but keep its ABI JAR the same.  This should *not* affect the input-based
    // rule key of the consuming java library, since it only cares about the contents of the ABI
    // JAR.
    resolver = new BuildRuleResolver();
    exportedDep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"))
            .addSrc(Paths.get("Source1.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("different JAR contents", exportedDep.getPathToOutput());
    dep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addExportedDep(exportedDep.getBuildTarget())
            .build(resolver, filesystem);
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep.getBuildTarget())
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.equalTo(unaffectedRuleKey));

    // Now actually change the exproted Java library dependency's ABI JAR.  This *should* affect
    // the input-based rule key of the consuming java library.
    resolver = new BuildRuleResolver();
    pathResolver = new SourcePathResolver(resolver);
    exportedDep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"))
            .addSrc(Paths.get("Source1.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath(
        "changed ABI JAR contents",
        pathResolver.deprecatedGetPath(exportedDep.getAbiJar().get()));
    dep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addExportedDep(exportedDep.getBuildTarget())
            .build(resolver, filesystem);
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep.getBuildTarget())
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(Matchers.equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with a Java library dep exported through
   * multiple Java library dependencies.
   */
  @Test
  public void testInputBasedRuleKeyWithRecursiveExportedDeps() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep exporting another Java
    // library dep.
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    JavaLibrary exportedDep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"))
            .addSrc(Paths.get("Source1.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("JAR contents", exportedDep.getPathToOutput());
    filesystem.writeContentsToPath(
        "ABI JAR contents",
        pathResolver.deprecatedGetPath(exportedDep.getAbiJar().get()));
    JavaLibrary dep2 =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep2"))
            .addExportedDep(exportedDep.getBuildTarget())
            .build(resolver, filesystem);
    JavaLibrary dep1 =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep1"))
            .addExportedDep(dep2.getBuildTarget())
            .build(resolver, filesystem);
    JavaLibrary library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep1.getBuildTarget())
            .build(resolver, filesystem);
    InputBasedRuleKeyBuilderFactory factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey originalRuleKey = factory.build(library);

    // Now change the exported Java library dependency such that its rule key changes, and change
    // its JAR contents, but keep its ABI JAR the same.  This should *not* affect the input-based
    // rule key of the consuming java library, since it only cares about the contents of the ABI
    // JAR.
    resolver = new BuildRuleResolver();
    exportedDep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"))
            .addSrc(Paths.get("Source1.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("different JAR contents", exportedDep.getPathToOutput());
    dep2 =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep2"))
            .addExportedDep(exportedDep.getBuildTarget())
            .build(resolver, filesystem);
    dep1 =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep1"))
            .addExportedDep(dep2.getBuildTarget())
            .build(resolver, filesystem);
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep1.getBuildTarget())
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.equalTo(unaffectedRuleKey));

    // Now actually change the exproted Java library dependency's ABI JAR.  This *should* affect
    // the input-based rule key of the consuming java library.
    resolver = new BuildRuleResolver();
    pathResolver = new SourcePathResolver(resolver);
    exportedDep =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:edep"))
            .addSrc(Paths.get("Source1.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath(
        "changed ABI JAR contents",
        pathResolver.deprecatedGetPath(exportedDep.getAbiJar().get()));
    dep2 =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep2"))
            .addExportedDep(exportedDep.getBuildTarget())
            .build(resolver, filesystem);
    dep1 =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep1"))
            .addExportedDep(dep2.getBuildTarget())
            .build(resolver, filesystem);
    library =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(dep1.getBuildTarget())
            .build(resolver, filesystem);
    factory =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            new SourcePathResolver(resolver));
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(Matchers.equalTo(affectedRuleKey)));
  }

  private static BuildRule createDefaultJavaLibraryRuleWithAbiKey(
      BuildTarget buildTarget,
      ImmutableSet<String> srcs,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<BuildRule> exportedDeps) {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ImmutableSortedSet<SourcePath> srcsAsPaths = FluentIterable.from(srcs)
        .transform(MorePaths.TO_PATH)
        .transform(SourcePaths.toSourcePath(projectFilesystem))
        .toSortedSet(Ordering.natural());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
        .build();

    return new DefaultJavaLibrary(
        buildRuleParams,
        new SourcePathResolver(new BuildRuleResolver()),
        srcsAsPaths,
        /* resources */ ImmutableSet.<SourcePath>of(),
        DEFAULT_JAVAC_OPTIONS.getGeneratedSourceFolderName(),
        /* proguardConfig */ Optional.<SourcePath>absent(),
        /* postprocessClassesCommands */ ImmutableList.<String>of(),
        exportedDeps,
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* abiJar */ new FakeSourcePath("abi.jar"),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        new JavacStepFactory(DEFAULT_JAVAC_OPTIONS, JavacOptionsAmender.IDENTITY),
        /* resourcesRoot */ Optional.<Path>absent(),
        /* mavenCoords */ Optional.<String>absent(),
        /* tests */ ImmutableSortedSet.<BuildTarget>of()) {
        };
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

    BuildContext context = createSuggestContext(ruleResolver);

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
        .addResource(new FakeSourcePath("becgkaifhjd.txt"))
        .addResource(new FakeSourcePath("bkhajdifcge.txt"))
        .addResource(new FakeSourcePath("cabfghjekid.txt"))
        .addResource(new FakeSourcePath("chkdbafijge.txt"))
        .build(resolver1, filesystem);

    BuildRuleResolver resolver2 = new BuildRuleResolver();
    SourcePathResolver pathResolver2 = new SourcePathResolver(resolver2);
    DefaultJavaLibrary rule2 = (DefaultJavaLibrary) JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addResource(new FakeSourcePath("chkdbafijge.txt"))
        .addResource(new FakeSourcePath("cabfghjekid.txt"))
        .addResource(new FakeSourcePath("bkhajdifcge.txt"))
        .addResource(new FakeSourcePath("becgkaifhjd.txt"))
        .build(resolver2, filesystem);

    ImmutableMap.Builder<String, String> fileHashes = ImmutableMap.builder();
    for (String filename : ImmutableList.of(
        "agifhbkjdec.java", "bdeafhkgcji.java", "bdehgaifjkc.java", "cfiabkjehgd.java",
        "becgkaifhjd.txt", "bkhajdifcge.txt", "cabfghjekid.txt", "chkdbafijge.txt")) {
      fileHashes.put(filename, Hashing.sha1().hashString(filename, Charsets.UTF_8).toString());
    }
    RuleKeyBuilderFactory ruleKeyBuilderFactory1 =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(fileHashes.build()),
            pathResolver1);
    RuleKeyBuilderFactory ruleKeyBuilderFactory2 =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(fileHashes.build()),
            pathResolver2);

    RuleKey key1 = ruleKeyBuilderFactory1.build(rule1);
    RuleKey key2 = ruleKeyBuilderFactory2.build(rule2);
    assertEquals(key1, key2);
  }

  @Test
  public void testAddPostprocessClassesCommands() {
    ImmutableList<String> postprocessClassesCommands = ImmutableList.of("tool arg1", "tool2");
    Path outputDirectory = SCRATCH_PATH.resolve("android/java/lib__java__classes");
    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.addAll(
        DefaultJavaLibrary.addPostprocessClassesCommands(
            new FakeProjectFilesystem().getRootPath(),
            postprocessClassesCommands,
            outputDirectory));

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

  private BuildContext createSuggestContext(BuildRuleResolver ruleResolver) {
    ActionGraph graph = RuleMap.createGraphFromBuildRules(ruleResolver);

    BuildContext context = EasyMock.createMock(BuildContext.class);
    expect(context.getActionGraph()).andReturn(graph).anyTimes();

    replay(context);

    return context;
  }

  // TODO(mbolin): Eliminate the bootclasspath parameter, as it is completely misused in this test.
  private BuildContext createBuildContext(BuildRule javaLibrary,
                                          @Nullable String bootclasspath,
                                          @Nullable ProjectFilesystem projectFilesystem) {
    AndroidPlatformTarget platformTarget = EasyMock.createMock(AndroidPlatformTarget.class);
    ImmutableList<Path> bootclasspathEntries = (bootclasspath == null)
        ? ImmutableList.of(Paths.get("I am not used"))
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
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setArtifactCache(new NoopArtifactCache())
        .setJavaPackageFinder(EasyMock.createMock(JavaPackageFinder.class))
        .setAndroidBootclasspathSupplier(
            BuildContext.createBootclasspathSupplier(Suppliers.ofInstance(platformTarget)))
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
          .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
          .setDebugEnabled(true)
          .build();

      ImmutableList<String> options = javacCommand.getOptions(executionContext,
          /* buildClasspathEntries */ ImmutableSortedSet.<Path>of());

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
      JavacOptions.Builder options = JavacOptions.builder(DEFAULT_JAVAC_OPTIONS)
          .setAnnotationProcessingParams(params);

      BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
          .setProjectFilesystem(projectFilesystem)
          .build();

      return new AndroidLibrary(
          buildRuleParams,
          new SourcePathResolver(new BuildRuleResolver()),
          ImmutableSet.of(new FakeSourcePath(src)),
          /* resources */ ImmutableSet.<SourcePath>of(),
          /* proguardConfig */ Optional.<SourcePath>absent(),
          /* postprocessClassesCommands */ ImmutableList.<String>of(),
          /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
          /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
          /* abiJar */ new FakeSourcePath("abi.jar"),
          /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
          options.build(),
          /* resourcesRoot */ Optional.<Path>absent(),
          /* mavenCoords */ Optional.<String>absent(),
          /* manifestFile */ Optional.<SourcePath>absent(),
          /* tests */ ImmutableSortedSet.<BuildTarget>of());
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

}
