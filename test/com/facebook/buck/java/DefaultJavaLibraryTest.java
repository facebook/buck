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
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRuleBuilderParams;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class DefaultJavaLibraryTest {
  private static final String ANNOTATION_SCENARIO_TARGET =
      "//android/java/src/com/facebook:fb";
  private static final String ANNOTATION_SCENARIO_GEN_PATH =
      BuckConstant.ANNOTATION_DIR + "/android/java/src/com/facebook/__fb_gen__";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private BuildContext stubContext;

  @Before
  public void stubOutBuildContext() {
    File root = new File(".");
    StepRunner stepRunner = createNiceMock(StepRunner.class);
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);
    replay(packageFinder, stepRunner);

    stubContext = BuildContext.builder()
        .setArtifactCache(new NoopArtifactCache())
        .setDependencyGraph(new DependencyGraph(new MutableDirectedGraph<BuildRule>()))
        .setEventBus(BuckEventBusFactory.newInstance())
        .setJavaPackageFinder(packageFinder)
        .setProjectFilesystem(new ProjectFilesystem(root))
        .setStepRunner(stepRunner)
        .build();
  }


  @Test
  public void testAddResourceCommandsWithBuildFileParentOfSrcDirectory() {
    // Files:
    // android/java/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = new BuildTarget("//android/java", "resources");
    DefaultJavaLibrary javaRule = new DefaultJavaLibrary(
        new FakeBuildRuleParams(buildTarget),
        /* srcs */ ImmutableSet.<Path>of(),
        ImmutableSet.of(
            new FileSourcePath("android/java/src/com/facebook/base/data.json"),
            new FileSourcePath("android/java/src/com/facebook/common/util/data.json")
        ),
        /* proguardConfig */ Optional.<Path>absent(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<String>of(),
        JavacOptions.DEFAULTS
        );

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();
    javaRule.addResourceCommands(stubContext,
        commands, BIN_PATH.resolve("android/java/lib__resources__classes"), javaPackageFinder);
    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/base/data.json"),
            BIN_PATH.resolve("android/java/lib__resources__classes/com/facebook/base/data.json")),
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/common/util/data.json"),
            BIN_PATH.resolve(
                "android/java/lib__resources__classes/com/facebook/common/util/data.json")));
    MoreAsserts.assertListEquals(expected, commands.build());
  }

  @Test
  public void testAddResourceCommandsWithBuildFileParentOfJavaPackage() {
    // Files:
    // android/java/src/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = new BuildTarget("//android/java/src", "resources");
    DefaultJavaLibrary javaRule = new DefaultJavaLibrary(
        new FakeBuildRuleParams(buildTarget),
        /* srcs */ ImmutableSet.<Path>of(),
        ImmutableSet.<SourcePath>of(
            new FileSourcePath("android/java/src/com/facebook/base/data.json"),
            new FileSourcePath("android/java/src/com/facebook/common/util/data.json")
        ),
        /* proguargConfig */ Optional.<Path>absent(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<String>of(),
        JavacOptions.DEFAULTS);

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();
    javaRule.addResourceCommands(stubContext,
        commands, BIN_PATH.resolve("android/java/src/lib__resources__classes"), javaPackageFinder);
    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/base/data.json"),
            BIN_PATH.resolve(
                "android/java/src/lib__resources__classes/com/facebook/base/data.json")),
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/common/util/data.json"),
            BIN_PATH.resolve(
                "android/java/src/lib__resources__classes/com/facebook/common/util/data.json")));
    assertEquals(expected, commands.build());
    MoreAsserts.assertListEquals(expected, commands.build());
  }

  @Test
  public void testAddResourceCommandsWithBuildFileInJavaPackage() {
    // Files:
    // android/java/src/com/facebook/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = new BuildTarget("//android/java/src/com/facebook", "resources");
    DefaultJavaLibrary javaRule = new DefaultJavaLibrary(
        new FakeBuildRuleParams(buildTarget),
        /* srcs */ ImmutableSet.<Path>of(),
        ImmutableSet.of(
            new FileSourcePath("android/java/src/com/facebook/base/data.json"),
            new FileSourcePath("android/java/src/com/facebook/common/util/data.json")
        ),
        /* proguargConfig */ Optional.<Path>absent(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* additionalClasspathEntries */ ImmutableSet.<String>of(),
        JavacOptions.DEFAULTS);

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();
    javaRule.addResourceCommands(
        stubContext,
        commands,
        BIN_PATH.resolve("android/java/src/com/facebook/lib__resources__classes"),
        javaPackageFinder);
    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/base/data.json"),
            BIN_PATH.resolve(
                "android/java/src/com/facebook/lib__resources__classes/" +
                    "com/facebook/base/data.json")),
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/common/util/data.json"),
            BIN_PATH.resolve(
                "android/java/src/com/facebook/lib__resources__classes/" +
                    "com/facebook/common/util/data.json")));
    MoreAsserts.assertListEquals(expected, commands.build());
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

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());
    DefaultJavaLibrary javaLibrary = ruleResolver.buildAndAddToIndex(
        AndroidLibrary.newAndroidLibraryRuleBuilder(
            new BuildRuleBuilderParams(
                projectFilesystem,
                new FakeRuleKeyBuilderFactory())
        )
            .setBuildTarget(buildTarget)
            .addSrc(src));

    String bootclasspath = "effects.jar:maps.jar:usb.jar:";
    BuildContext context = createBuildContext(javaLibrary, bootclasspath, projectFilesystem);

    List<Step> steps = javaLibrary.getBuildSteps(context, new FakeBuildableContext());

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
  }

  @Test
  public void testGetInputsToCompareToOutputWhenAResourceAsSourcePathExists() {
    BuildRuleBuilderParams params = new FakeBuildRuleBuilderParams();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget genruleBuildTarget = BuildTargetFactory.newInstance("//generated:stuff");
    BuildRule genrule = GenruleBuilder.createGenrule(genruleBuildTarget)
        .setBash("echo 'aha' > $OUT")
        .setOut("stuff.txt")
        .build();
    ruleResolver.addToIndex(genruleBuildTarget, genrule);

    DefaultJavaLibrary javaRule = DefaultJavaLibrary.newJavaLibraryRuleBuilder(params)
        .setBuildTarget(BuildTargetFactory.newInstance("//library:code"))
        .addResource(new BuildTargetSourcePath(genruleBuildTarget))
        .addResource(new FileSourcePath("library/data.txt"))
        .build(ruleResolver);

    assertEquals(
        "Generated files should not be included in getInputsToCompareToOutput() because they " +
            "should not be part of the RuleKey computation.",
        ImmutableList.of(Paths.get("library/data.txt")),
        javaRule.getInputsToCompareToOutput());
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
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor," + AbiWriterProtocol.ABI_ANNOTATION_PROCESSOR_CLASS_NAME));
    assertEquals(
        "Expected '-s " + ANNOTATION_SCENARIO_GEN_PATH + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(ANNOTATION_SCENARIO_GEN_PATH));

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
    assertHasProcessor(parameters, "MyProcessor");
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
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);
  }

  @Test
  public void testAndroidAnnotation() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget processorTarget = BuildTargetFactory.newInstance("//java/processor:processor");
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(processorTarget)
            .addSrc(Paths.get("java/processor/processor.java")));

    BuildTarget libTarget = BuildTargetFactory.newInstance("//java/lib:lib");
    AndroidLibrary.Builder builder = AndroidLibrary.newAndroidLibraryRuleBuilder(
        new FakeBuildRuleBuilderParams())
        .setBuildTarget(libTarget);
    builder.getAnnotationProcessingBuilder()
        .addAllProcessors(ImmutableList.of("MyProcessor"))
        .addProcessorBuildTarget(processorTarget);

    AndroidLibrary rule = (AndroidLibrary) ruleResolver.buildAndAddToIndex(builder);

    AnnotationProcessingData processingData = rule.getAnnotationProcessingData();
    assertNotNull(processingData.getGeneratedSourceFolderName());
  }

  @Test
  public void testGetClasspathEntriesMap() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    JavaLibrary libraryOne = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java")));

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    JavaLibrary libraryTwo = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(BuildTargetFactory.newInstance("//:libone")));

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    JavaLibrary parent = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(parentTarget)
            .addSrc(Paths.get("java/src/com/parent/Meh.java"))
            .addDep(BuildTargetFactory.newInstance("//:libtwo")));

    assertEquals(ImmutableSetMultimap.of(
        libraryOne, "buck-out/gen/lib__libone__output/libone.jar",
        libraryTwo, "buck-out/gen/lib__libtwo__output/libtwo.jar",
        parent, "buck-out/gen/lib__parent__output/parent.jar"),
        parent.getTransitiveClasspathEntries());
  }

  @Test
  public void testClasspathForJavacCommand() throws IOException {
    // libraryOne responds like an ordinary prebuilt_jar with no dependencies. We have to use a
    // FakeJavaLibraryRule so that we can override the behavior of getAbiKey().
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    FakeJavaLibraryRule libraryOne = new FakeJavaLibraryRule(libraryOneTarget) {
      @Override
      public Sha1HashCode getAbiKey() {
        return new Sha1HashCode(Strings.repeat("cafebabe", 5));
      }

      @Override
      public ImmutableSetMultimap<JavaLibrary, String> getDeclaredClasspathEntries() {
        return ImmutableSetMultimap.<JavaLibrary, String>builder()
            .put(this, "java/src/com/libone/bar.jar")
            .build();
      }

      @Override
      public ImmutableSetMultimap<JavaLibrary, String> getOutputClasspathEntries() {
        return ImmutableSetMultimap.<JavaLibrary, String>builder()
            .put(this, "java/src/com/libone/bar.jar")
            .build();
      }

      @Override
      public ImmutableSetMultimap<JavaLibrary, String> getTransitiveClasspathEntries() {
        return ImmutableSetMultimap.of();
      }
    };

    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(libraryOneTarget, libraryOne);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    JavaLibrary libraryTwo = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(libraryOneTarget));

    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    expect(buildContext.getBuildDependencies()).andReturn(BuildDependencies.FIRST_ORDER_ONLY)
        .times(2);
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    expect(buildContext.getJavaPackageFinder()).andReturn(javaPackageFinder);

    replay(buildContext, javaPackageFinder);

    List<Step> steps = libraryTwo.getBuildSteps(buildContext, new FakeBuildableContext());

    EasyMock.verify(buildContext, javaPackageFinder);

    ImmutableList<JavacInMemoryStep> javacSteps = FluentIterable
        .from(steps)
        .filter(JavacInMemoryStep.class)
        .toList();
    assertEquals("There should be only one javac step.", 1, javacSteps.size());
    JavacInMemoryStep javacStep = javacSteps.get(0);
    assertEquals(
        "The classpath to use when compiling //:libtwo according to getDeclaredClasspathEntries()" +
            " should contain only bar.jar.",
        ImmutableSet.of("java/src/com/libone/bar.jar"),
        ImmutableSet.copyOf(libraryTwo.getDeclaredClasspathEntries().values()));
    assertEquals(
        "The classpath for the javac step to compile //:libtwo should contain only bar.jar.",
        ImmutableSet.of("java/src/com/libone/bar.jar"),
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
    MoreAsserts.assertContainsOne(parameters, ANNOTATION_SCENARIO_GEN_PATH);
    MoreAsserts.assertContainsOne(parameters, "-proc:only");

    assertEquals(
        "Expected '-processor MyProcessor' parameters",
        parameters.indexOf("-processor") + 1,
        parameters.indexOf("MyProcessor," + AbiWriterProtocol.ABI_ANNOTATION_PROCESSOR_CLASS_NAME));
    assertEquals(
        "Expected '-s " + ANNOTATION_SCENARIO_GEN_PATH + "' parameters",
        parameters.indexOf("-s") + 1,
        parameters.indexOf(ANNOTATION_SCENARIO_GEN_PATH));

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
    JavaLibrary notIncluded = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(nonIncludedTarget)
            .addSrc(Paths.get("java/src/com/not_included/Raz.java")));

    BuildTarget includedTarget = BuildTargetFactory.newInstance("//:included");
    JavaLibrary included = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(includedTarget)
            .addSrc(Paths.get("java/src/com/included/Rofl.java")));

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    JavaLibrary libraryOne = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(libraryOneTarget)
            .addDep(BuildTargetFactory.newInstance("//:not_included"))
            .addDep(BuildTargetFactory.newInstance("//:included"))
            .addExportedDep(BuildTargetFactory.newInstance("//:included"))
            .addSrc(Paths.get("java/src/com/libone/Bar.java")));

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    JavaLibrary libraryTwo = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(libraryTwoTarget)
        .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
        .addDep(BuildTargetFactory.newInstance("//:libone"))
        .addExportedDep(BuildTargetFactory.newInstance("//:libone")));

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    JavaLibrary parent = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(BuildTargetFactory.newInstance("//:libtwo")));

    assertEquals(
        "A java_library that depends on //:libone should include only libone.jar in its " +
            "classpath when compiling itself.",
        ImmutableSetMultimap.builder()
            .put(notIncluded, "buck-out/gen/lib__not_included__output/not_included.jar")
            .build(),
        notIncluded.getOutputClasspathEntries());

    assertEquals(
        ImmutableSetMultimap.builder()
            .put(included, "buck-out/gen/lib__included__output/included.jar")
            .build(),
        included.getOutputClasspathEntries());

    assertEquals(
        ImmutableSetMultimap.builder()
            .put(included, "buck-out/gen/lib__included__output/included.jar")
            .put(libraryOne, "buck-out/gen/lib__libone__output/libone.jar")
            .put(libraryOne, "buck-out/gen/lib__included__output/included.jar")
            .build(),
        libraryOne.getOutputClasspathEntries());

    assertEquals(
        "//:libtwo exports its deps, so a java_library that depends on //:libtwo should include " +
            "both libone.jar and libtwo.jar in its classpath when compiling itself.",
        ImmutableSetMultimap.builder()
            .put(libraryOne, "buck-out/gen/lib__libone__output/libone.jar")
            .put(libraryOne, "buck-out/gen/lib__included__output/included.jar")
            .put(libraryTwo, "buck-out/gen/lib__libone__output/libone.jar")
            .put(libraryTwo, "buck-out/gen/lib__libtwo__output/libtwo.jar")
            .put(libraryTwo, "buck-out/gen/lib__included__output/included.jar")
            .build(),
        libraryTwo.getOutputClasspathEntries());

    assertEquals(
        "A java_binary that depends on //:parent should include libone.jar, libtwo.jar and " +
            "parent.jar.",
        ImmutableSetMultimap.builder()
            .put(included, "buck-out/gen/lib__included__output/included.jar")
            .put(notIncluded, "buck-out/gen/lib__not_included__output/not_included.jar")
            .put(libraryOne, "buck-out/gen/lib__included__output/included.jar")
            .put(libraryOne, "buck-out/gen/lib__libone__output/libone.jar")
            .put(libraryTwo, "buck-out/gen/lib__included__output/included.jar")
            .put(libraryTwo, "buck-out/gen/lib__not_included__output/not_included.jar")
            .put(libraryTwo, "buck-out/gen/lib__libone__output/libone.jar")
            .put(libraryTwo, "buck-out/gen/lib__libtwo__output/libtwo.jar")
            .put(parent, "buck-out/gen/lib__parent__output/parent.jar")
            .build(),
        parent.getTransitiveClasspathEntries());

    assertEquals(
        "A java_library that depends on //:parent should include only parent.jar in its " +
            "-classpath when compiling itself.",
        ImmutableSetMultimap.builder()
            .put(parent, "buck-out/gen/lib__parent__output/parent.jar")
            .build(),
        parent.getOutputClasspathEntries());
  }

  /**
   * Tests DefaultJavaLibraryRule#getAbiKeyForDeps() when the dependencies contain a JavaAbiRule
   * that is not a JavaLibraryRule.
   */
  @Test
  public void testGetAbiKeyForDepsWithMixedDeps() {
    String tinyLibAbiKeyHash = Strings.repeat("a", 40);
    BuildRule tinyLibrary = createDefaultJavaLibaryRuleWithAbiKey(
        tinyLibAbiKeyHash,
        BuildTargetFactory.newInstance("//:tinylib"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    String javaAbiRuleKeyHash = Strings.repeat("b", 40);
    FakeJavaAbiRule fakeJavaAbiRule = new FakeJavaAbiRule(
        AndroidResourceDescription.TYPE,
        BuildTargetFactory.newInstance("//:tinylibfakejavaabi"),
        javaAbiRuleKeyHash);

    DefaultJavaLibrary defaultJavaLibary = createDefaultJavaLibaryRuleWithAbiKey(
        Strings.repeat("c", 40),
        BuildTargetFactory.newInstance("//:javalib"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSet.<BuildRule>of(tinyLibrary, fakeJavaAbiRule),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putUnencodedChars(tinyLibAbiKeyHash);
    hasher.putUnencodedChars(javaAbiRuleKeyHash);

    assertEquals(new Sha1HashCode(hasher.hash().toString()), defaultJavaLibary.getAbiKeyForDeps());
  }

  /**
   * @see DefaultJavaLibrary#getAbiKeyForDeps()
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
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Create two java_library rules, each of which depends on //:tinylib, but only one of which
    // exports its deps.
    String commonWithExportAbiKeyHash = Strings.repeat("b", 40);
    DefaultJavaLibrary commonWithExport = createDefaultJavaLibaryRuleWithAbiKey(
        commonWithExportAbiKeyHash,
        BuildTargetFactory.newInstance("//:common_with_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSet.<BuildRule>of(tinyLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(tinyLibrary));
    DefaultJavaLibrary commonNoExport = createDefaultJavaLibaryRuleWithAbiKey(
        /* abiHash */ null,
        BuildTargetFactory.newInstance("//:common_no_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSet.<BuildRule>of(tinyLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Verify getAbiKeyForDeps() for the two //:common_XXX rules.
    assertEquals(
        "getAbiKeyForDeps() should be the same for both rules because they have the same deps.",
        commonNoExport.getAbiKeyForDeps(),
        commonWithExport.getAbiKeyForDeps());
    String expectedAbiKeyForDepsHash = Hashing.sha1().newHasher()
        .putUnencodedChars(tinyLibAbiKeyHash).hash().toString();
    String observedAbiKeyForDepsHash = commonNoExport.getAbiKeyForDeps().getHash();
    assertEquals(expectedAbiKeyForDepsHash, observedAbiKeyForDepsHash);

    // Create a BuildRuleResolver populated with the three build rules we created thus far.
    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(tinyLibrary.getBuildTarget(), tinyLibrary);
    buildRuleIndex.put(commonWithExport.getBuildTarget(), commonWithExport);
    buildRuleIndex.put(commonNoExport.getBuildTarget(), commonNoExport);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    // Create two rules, each of which depends on one of the //:common_XXX rules.
    DefaultJavaLibrary consumerNoExport = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:consumer_no_export"))
        .addDep(BuildTargetFactory.newInstance("//:common_no_export")));
    DefaultJavaLibrary consumerWithExport = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:consumer_with_export"))
        .addDep(BuildTargetFactory.newInstance("//:common_with_export")));

    // Verify getAbiKeyForDeps() for the two //:consumer_XXX rules.
    assertEquals(
        "The ABI of the deps of //:consumer_no_export should be the empty ABI.",
        new Sha1HashCode(AbiWriterProtocol.EMPTY_ABI_KEY),
        consumerNoExport.getAbiKeyForDeps());
    assertThat(
        "Although //:consumer_no_export and //:consumer_with_export have the same deps, " +
        "the ABIs of their deps will differ because of the use of exported_deps is non-empty",
        consumerNoExport.getAbiKeyForDeps(),
        not(equalTo(consumerWithExport.getAbiKeyForDeps())));
    String expectedAbiKeyNoDepsHashForConsumerWithExport = Hashing.sha1().newHasher()
        .putUnencodedChars(commonWithExport.getAbiKey().getHash())
        .putUnencodedChars(tinyLibAbiKeyHash)
        .hash()
        .toString();
    String observedAbiKeyNoDepsHashForConsumerWithExport = consumerWithExport.getAbiKeyForDeps()
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
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of());

    // Create two java_library rules, each of which depends on //:commonlib, but only one of which
    // exports its deps.
    String libWithExportAbiKeyHash = Strings.repeat("b", 40);
    DefaultJavaLibrary libWithExport = createDefaultJavaLibaryRuleWithAbiKey(
        libWithExportAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_with_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSet.<BuildRule>of(commonLibrary),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(commonLibrary));
    String libNoExportAbiKeyHash = Strings.repeat("c", 40);
    DefaultJavaLibrary libNoExport = createDefaultJavaLibaryRuleWithAbiKey(
        /* abiHash */ libNoExportAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_no_export"),
        /* srcs */ ImmutableSet.<String>of(),
        /* deps */ ImmutableSet.<BuildRule>of(commonLibrary),
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
        libWithExport.getAbiKey().getHash());

    assertEquals(
        "getAbiKey() should not include the dependencies' ABI keys for the rule with " +
            "export_deps=false.",
        libNoExportAbiKeyHash,
        libNoExport.getAbiKey().getHash());
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
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* exporedtDeps */ ImmutableSet.<BuildRule>of());
    BuildRule libC = createDefaultJavaLibaryRuleWithAbiKey(
        libCAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_c"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(libD),
        /* exporedtDeps */ ImmutableSet.<BuildRule>of(libD));
    BuildRule libB = createDefaultJavaLibaryRuleWithAbiKey(
        libBAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_b"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(libC),
        /* exportedDeps */ ImmutableSet.<BuildRule>of(libC));
    JavaLibrary libA = createDefaultJavaLibaryRuleWithAbiKey(
        libAAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_a"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(libB),
        /* exportedDeps */ ImmutableSet.<BuildRule>of(libB));

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
        libA.getAbiKey().getHash());

    // Test the following dependency graph:
    // a(export_deps=true) -> b(export_deps=true) -> c(export_deps=false) -> d(export_deps=false)
    libD = createDefaultJavaLibaryRuleWithAbiKey(
        libDAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_d2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* exportedDeps */ ImmutableSet.<BuildRule>of());
    libC = createDefaultJavaLibaryRuleWithAbiKey(
        libCAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_c2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(libD),
        /* exportDeps */ ImmutableSet.<BuildRule>of());
    libB = createDefaultJavaLibaryRuleWithAbiKey(
        libBAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_b2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(libC),
        /* exportedDeps */ ImmutableSet.<BuildRule>of(libC));
    libA = createDefaultJavaLibaryRuleWithAbiKey(
        libAAbiKeyHash,
        BuildTargetFactory.newInstance("//:lib_a2"),
        // Must have a source file or else its ABI will be AbiWriterProtocol.EMPTY_ABI_KEY.
        /* srcs */ ImmutableSet.of("foo/Bar.java"),
        /* deps */ ImmutableSet.<BuildRule>of(libB),
        /* exportedDeps */ ImmutableSet.<BuildRule>of(libB));

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
        libA.getAbiKey().getHash());

  }

  private static DefaultJavaLibrary createDefaultJavaLibaryRuleWithAbiKey(
      @Nullable final String partialAbiHash,
      BuildTarget buildTarget,
      ImmutableSet<String> srcs,
      ImmutableSet<BuildRule> deps,
      ImmutableSet<BuildRule> exportedDeps) {
    ImmutableSortedSet<Path> srcsAsPaths = FluentIterable.from(srcs)
        .transform(MorePaths.TO_PATH)
        .toSortedSet(Ordering.natural());

    return new DefaultJavaLibrary(
        new FakeBuildRuleParams(buildTarget, ImmutableSortedSet.copyOf(deps)),
        srcsAsPaths,
        /* resources */ ImmutableSet.<SourcePath>of(),
        /* proguardConfig */ Optional.<Path>absent(),
        exportedDeps,
        /* additionalClasspathEntries */ ImmutableSet.<String>of(),
        JavacOptions.DEFAULTS) {
      @Override
      public Sha1HashCode getAbiKey() {
        if (partialAbiHash == null) {
          return super.getAbiKey();
        } else {
          return createTotalAbiKey(new Sha1HashCode(partialAbiHash));
        }
      }
    };
  }

  @Test
  public void testEmptySuggestBuildFunction() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    DefaultJavaLibrary libraryOne = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/bar.java")));

    BuildContext context = createSuggestContext(ruleResolver,
        BuildDependencies.FIRST_ORDER_ONLY);

    ImmutableSetMultimap<JavaLibrary, String> classpathEntries =
        libraryOne.getTransitiveClasspathEntries();

    assertEquals(
        Optional.<JavacInMemoryStep.SuggestBuildRules>absent(),
        libraryOne.createSuggestBuildFunction(context,
            classpathEntries,
            classpathEntries,
            createJarResolver(/* classToSymbols */ImmutableMap.<String, String>of())));

    EasyMock.verify(context);
  }

  @Test
  public void testSuggsetDepsReverseTopoSortRespected() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    DefaultJavaLibrary libraryOne = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(libraryOneTarget)
        .addSrc(Paths.get("java/src/com/libone/Bar.java"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    DefaultJavaLibrary libraryTwo = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(libraryTwoTarget)
            .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
            .addDep(BuildTargetFactory.newInstance("//:libone")));

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    DefaultJavaLibrary parent = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
        .setBuildTarget(parentTarget)
        .addSrc(Paths.get("java/src/com/parent/Meh.java"))
        .addDep(BuildTargetFactory.newInstance("//:libtwo"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    BuildTarget grandparentTarget = BuildTargetFactory.newInstance("//:grandparent");
    DefaultJavaLibrary grandparent = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(grandparentTarget)
            .addSrc(Paths.get("java/src/com/parent/OldManRiver.java"))
            .addDep(BuildTargetFactory.newInstance("//:parent")));

    BuildContext context = createSuggestContext(ruleResolver,
        BuildDependencies.WARN_ON_TRANSITIVE);

    ImmutableSetMultimap<JavaLibrary, String> transitive =
        parent.getTransitiveClasspathEntries();

    ImmutableMap<String, String> classToSymbols = ImmutableMap.of(
        Iterables.getFirst(transitive.get(parent), null), "com.facebook.Foo",
        Iterables.getFirst(transitive.get(libraryOne), null), "com.facebook.Bar",
        Iterables.getFirst(transitive.get(libraryTwo), null), "com.facebook.Foo");

    Optional<JavacInMemoryStep.SuggestBuildRules> suggestFn =
        grandparent.createSuggestBuildFunction(context,
            transitive,
            /* declaredClasspathEntries */ ImmutableSetMultimap.<JavaLibrary, String>of(),
            createJarResolver(classToSymbols));

    assertTrue(suggestFn.isPresent());
    assertEquals(ImmutableSet.of("//:parent", "//:libone"),
                 suggestFn.get().suggest(projectFilesystem,
                                         ImmutableSet.of("com.facebook.Foo", "com.facebook.Bar")));

    EasyMock.verify(context);
  }

  @Test
  public void testRuleKeyIsOrderInsensitiveForSourcesAndResources() throws IOException {
    BuildRuleBuilderParams params = new FakeBuildRuleBuilderParams();

    // Note that these filenames were deliberately chosen to have identical hashes to maximize
    // the chance of order-sensitivity when being inserted into a HashMap.  Just using
    // {foo,bar}.{java,txt} resulted in a passing test even for the old broken code.

    DefaultJavaLibrary rule1 = DefaultJavaLibrary.newJavaLibraryRuleBuilder(params)
        .setBuildTarget(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addResource(new FileSourcePath("becgkaifhjd.txt"))
        .addResource(new FileSourcePath("bkhajdifcge.txt"))
        .addResource(new FileSourcePath("cabfghjekid.txt"))
        .addResource(new FileSourcePath("chkdbafijge.txt"))
        .build(new BuildRuleResolver());

    DefaultJavaLibrary rule2 = DefaultJavaLibrary.newJavaLibraryRuleBuilder(params)
        .setBuildTarget(BuildTargetFactory.newInstance("//lib:lib"))
        .addSrc(Paths.get("cfiabkjehgd.java"))
        .addSrc(Paths.get("bdehgaifjkc.java"))
        .addSrc(Paths.get("bdeafhkgcji.java"))
        .addSrc(Paths.get("agifhbkjdec.java"))
        .addResource(new FileSourcePath("chkdbafijge.txt"))
        .addResource(new FileSourcePath("cabfghjekid.txt"))
        .addResource(new FileSourcePath("bkhajdifcge.txt"))
        .addResource(new FileSourcePath("becgkaifhjd.txt"))
        .build(new BuildRuleResolver());

    Collection<Path> inputs1 = rule1.getInputsToCompareToOutput();
    Collection<Path> inputs2 = rule2.getInputsToCompareToOutput();
    assertEquals(ImmutableList.copyOf(inputs1), ImmutableList.copyOf(inputs2));

    ImmutableMap.Builder<String, String> fileHashes = ImmutableMap.builder();
    for (String filename : ImmutableList.of(
        "agifhbkjdec.java", "bdeafhkgcji.java", "bdehgaifjkc.java", "cfiabkjehgd.java",
        "becgkaifhjd.txt", "bkhajdifcge.txt", "cabfghjekid.txt", "chkdbafijge.txt")) {
      fileHashes.put(filename, Hashing.sha1().hashString(filename, Charsets.UTF_8).toString());
    }
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(FakeFileHashCache.createFromStrings(fileHashes.build()));

    RuleKey.Builder builder1 = ruleKeyBuilderFactory.newInstance(rule1);
    RuleKey.Builder builder2 = ruleKeyBuilderFactory.newInstance(rule2);
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
    DefaultJavaLibrary rule = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java")));

    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    rule.createCommandsForJavac(
        rule.getPathToOutputFile(),
        ImmutableSet.copyOf(rule.getTransitiveClasspathEntries().values()),
        ImmutableSet.copyOf(rule.getDeclaredClasspathEntries().values()),
        JavacOptions.DEFAULTS,
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacStep.SuggestBuildRules>absent(),
        stepsBuilder,
        libraryOneTarget
    );


    List<Step> steps = stepsBuilder.build();
    assertEquals(steps.size(), 3);
    assertTrue(steps.get(2) instanceof JavacInMemoryStep);
  }

  @Test
  public void testWhenJavacIsProvidedAnExternalJavacStepIsAdded() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    DefaultJavaLibrary rule = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibrary.newJavaLibraryRuleBuilder(new FakeBuildRuleBuilderParams())
            .setBuildTarget(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java")));

    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    JavacOptions javacOptions = JavacOptions.builder(JavacOptions.DEFAULTS)
        .setJavaCompilerEnviornment(
            new JavaCompilerEnvironment(
                Optional.of(Paths.get("javac")),
                Optional.<JavacVersion>absent())
        )
        .build();
    rule.createCommandsForJavac(
        rule.getPathToOutputFile(),
        ImmutableSet.copyOf(rule.getTransitiveClasspathEntries().values()),
        ImmutableSet.copyOf(rule.getDeclaredClasspathEntries().values()),
        javacOptions,
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacStep.SuggestBuildRules>absent(),
        stepsBuilder,
        libraryOneTarget
    );

    List<Step> steps = stepsBuilder.build();
    assertEquals(steps.size(), 3);
    assertTrue(steps.get(2) instanceof ExternalJavacStep);
  }


  // Utilities

  private DefaultJavaLibrary.JarResolver createJarResolver(
      final ImmutableMap<String, String> classToSymbols) {

    ImmutableSetMultimap.Builder<Path, String> resolveMapBuilder =
        ImmutableSetMultimap.builder();

    for (Map.Entry<String, String> entry : classToSymbols.entrySet()) {
      String fullyQualified = entry.getValue();
      String packageName = fullyQualified.substring(0, fullyQualified.lastIndexOf('.'));
      String className = fullyQualified.substring(fullyQualified.lastIndexOf('.'));
      resolveMapBuilder.putAll(Paths.get(entry.getKey()), fullyQualified, packageName, className);
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

  private JavaPackageFinder createJavaPackageFinder() {
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
        ImmutableSet.<String>of("/android/java/src"));
  }

  private BuildContext createSuggestContext(BuildRuleResolver ruleResolver,
                                            BuildDependencies buildDependencies) {
    DependencyGraph graph = RuleMap.createGraphFromBuildRules(ruleResolver);

    BuildContext context = EasyMock.createMock(BuildContext.class);
    expect(context.getDependencyGraph()).andReturn(graph).anyTimes();

    expect(context.getBuildDependencies()).andReturn(buildDependencies).anyTimes();

    replay(context);

    return context;
  }

  // TODO(mbolin): Eliminate the bootclasspath parameter, as it is completely misused in this test.
  private BuildContext createBuildContext(DefaultJavaLibrary javaLibrary,
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
    return BuildContext.builder()
        .setDependencyGraph(RuleMap.createGraphFromSingleRule(javaLibrary))
        .setStepRunner(EasyMock.createMock(StepRunner.class))
        .setProjectFilesystem(projectFilesystem)
        .setArtifactCache(new NoopArtifactCache())
        .setBuildDependencies(BuildDependencies.TRANSITIVE)
        .setJavaPackageFinder(EasyMock.createMock(JavaPackageFinder.class))
        .setAndroidBootclasspathForAndroidPlatformTarget(Optional.of(platformTarget))
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
       return JavaBinaryRuleBuilder.newBuilder(target)
            .setMainClass("com.facebook.Main")
            .build();
      }
    },
    VALID_JAVA_LIBRARY("//tools/java/src/com/facebook/somejava:library") {
      @Override
      public BuildRule createRule(BuildTarget target) {
        return new DefaultJavaLibrary(
            new FakeBuildRuleParams(target),
            ImmutableSet.of(Paths.get("MyClass.java")),
            ImmutableSet.<SourcePath>of(),
            Optional.of(Paths.get("MyProguardConfig")),
            /* exportedDeps */ ImmutableSet.<BuildRule>of(),
            /* additionalClasspathEntries */ ImmutableSet.<String>of(),
            JavacOptions.DEFAULTS);
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
    private final BuildRuleResolver ruleResolver;
    private ExecutionContext executionContext;
    private BuildContext buildContext;

    public AnnotationProcessingScenario() throws IOException {
      annotationProcessingParamsBuilder = new AnnotationProcessingParams.Builder();
      buildRuleIndex = Maps.newHashMap();
      ruleResolver = new BuildRuleResolver(buildRuleIndex);
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
      ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmp.getRoot());
      DefaultJavaLibrary javaLibrary = createJavaLibraryRule(projectFilesystem);
      buildContext = createBuildContext(javaLibrary, /* bootclasspath */ null, projectFilesystem);
      List<Step> steps = javaLibrary.getBuildSteps(buildContext, new FakeBuildableContext());
      JavacInMemoryStep javacCommand = lastJavacCommand(steps);

      executionContext = ExecutionContext.builder()
          .setProjectFilesystem(projectFilesystem)
          .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
          .setDebugEnabled(true)
          .setEventBus(BuckEventBusFactory.newInstance())
          .setPlatform(Platform.detect())
          .build();

      ImmutableList<String> options = javacCommand.getOptions(executionContext,
          /* buildClasspathEntries */ ImmutableSet.<String>of());

      return options;
    }

    // TODO(simons): Actually generate a java library rule, rather than an android one.
    private DefaultJavaLibrary createJavaLibraryRule(ProjectFilesystem projectFilesystem)
        throws IOException {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(ANNOTATION_SCENARIO_TARGET);
      annotationProcessingParamsBuilder.setOwnerTarget(buildTarget);

      tmp.newFolder("android", "java", "src", "com", "facebook");
      String src = "android/java/src/com/facebook/Main.java";
      tmp.newFile(src);

      AnnotationProcessingParams params = annotationProcessingParamsBuilder.build(ruleResolver);
      JavacOptions.Builder options = JavacOptions.builder().setAnnotationProcessingData(params);

      return new AndroidLibrary(
          new BuildRuleParams(
              buildTarget,
              /* deps */ ImmutableSortedSet.<BuildRule>of(),
              /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of(),
              projectFilesystem,
              new FakeRuleKeyBuilderFactory()),
          ImmutableSet.of(Paths.get(src)),
          /* resources */ ImmutableSet.<SourcePath>of(),
          /* proguardConfig */ Optional.<Path>absent(),
          /* exortDeps */ ImmutableSet.<BuildRule>of(),
          /* additionalClasspathEntries */ ImmutableSet.<String>of(),
          options.build(),
          /* manifestFile */ Optional.<Path>absent());
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
      return (JavacInMemoryStep) javac;
    }
  }

  private static class FakeJavaAbiRule extends FakeBuildRule implements HasJavaAbi {
    private final String abiKeyHash;

    public FakeJavaAbiRule(BuildRuleType type, BuildTarget buildTarget, String abiKeyHash) {
      super(type, buildTarget);
      this.abiKeyHash = abiKeyHash;
    }

    @Override
    public Sha1HashCode getAbiKey() {
      return new Sha1HashCode(abiKeyHash);
    }
  }
}
