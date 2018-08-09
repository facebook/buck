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
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.zip.CustomJarOutputStream;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DefaultJavaLibraryTest extends AbiCompilationModeTest {
  private static final String ANNOTATION_SCENARIO_TARGET = "//android/java/src/com/facebook:fb";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();
  private String annotationScenarioGenPath;
  private ActionGraphBuilder graphBuilder;
  private JavaBuckConfig testJavaBuckConfig;

  @Before
  public void setUp() throws InterruptedException {
    graphBuilder = new TestActionGraphBuilder();

    testJavaBuckConfig = getJavaBuckConfigWithCompilationMode();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath());
    StepRunner stepRunner = createNiceMock(StepRunner.class);
    JavaPackageFinder packageFinder = createNiceMock(JavaPackageFinder.class);
    replay(packageFinder, stepRunner);

    annotationScenarioGenPath =
        filesystem
            .resolve(filesystem.getBuckPaths().getAnnotationDir())
            .resolve("android/java/src/com/facebook/__fb_gen__")
            .toAbsolutePath()
            .toString();
  }

  /** Make sure that when isAndroidLibrary is true, that the Android bootclasspath is used. */
  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testBuildInternalWithAndroidBootclasspath() throws Exception {
    String folder = "android/java/src/com/facebook";
    tmp.newFolder(folder.split("/"));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//" + folder + ":fb");

    Path src = Paths.get(folder, "Main.java");
    tmp.newFile(src.toString());

    BuildRule libraryRule =
        AndroidLibraryBuilder.createBuilder(buildTarget).addSrc(src).build(graphBuilder);
    DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) libraryRule;

    BuildContext context = createBuildContext();

    List<Step> steps = javaLibrary.getBuildSteps(context, new FakeBuildableContext());

    // Find the JavacStep and verify its bootclasspath.
    JavacStep javac = getJavacStep(steps);
    assertEquals(
        "Should compile Main.java rather than generated R.java.",
        ImmutableSet.of(src),
        javac.getSrcs());
  }

  @Test
  public void testJavaLibaryThrowsIfResourceIsDirectory() {
    ProjectFilesystem filesystem =
        new AllExistingProjectFilesystem() {
          @Override
          public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
            return true;
          }
        };

    try {
      createJavaLibraryBuilder(BuildTargetFactory.newInstance("//library:code"))
          .addResource(FakeSourcePath.of("library"))
          .build(graphBuilder, filesystem);
      fail("An exception should have been thrown because a directory was passed as a resource.");
    } catch (HumanReadableException e) {
      assertTrue(e.getHumanReadableErrorMessage().contains("a directory is not a valid input"));
    }
  }

  /** Verify adding an annotation processor java binary. */
  @Test
  public void testAddAnnotationProcessorJavaBinary() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validJavaBinary);

    scenario
        .getAnnotationProcessingParamsBuilder()
        .setLegacyAnnotationProcessorNames(ImmutableList.of("MyProcessor"));

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
      assertThat("Expected no custom annotation options.", parameter.startsWith("-A"), is(false));
    }
  }

  /** Verify adding an annotation processor prebuilt jar. */
  @Test
  public void testAddAnnotationProcessorPrebuiltJar() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validPrebuiltJar);

    scenario
        .getAnnotationProcessingParamsBuilder()
        .setLegacyAnnotationProcessorNames(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  /** Verify adding an annotation processor java library. */
  @Test
  public void testAddAnnotationProcessorJavaLibrary() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validPrebuiltJar);

    scenario
        .getAnnotationProcessingParamsBuilder()
        .setLegacyAnnotationProcessorNames(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  /** Verify adding multiple annotation processors. */
  @Test
  public void testAddAnnotationProcessorJar() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validPrebuiltJar);
    scenario.addAnnotationProcessorTarget(validJavaBinary);
    scenario.addAnnotationProcessorTarget(validJavaLibrary);

    scenario
        .getAnnotationProcessingParamsBuilder()
        .setLegacyAnnotationProcessorNames(ImmutableList.of("MyProcessor"));

    ImmutableList<String> parameters = scenario.buildAndGetCompileParameters();

    MoreAsserts.assertContainsOne(parameters, "-processorpath");
    MoreAsserts.assertContainsOne(parameters, "-processor");
    assertHasProcessor(parameters, "MyProcessor");
    MoreAsserts.assertContainsOne(parameters, "-s");
    MoreAsserts.assertContainsOne(parameters, annotationScenarioGenPath);
  }

  @Test
  public void testGetClasspathEntriesMap() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?> libraryOne =
        createJavaLibraryBuilder(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java"))
            .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?> libraryTwo =
        createJavaLibraryBuilder(libraryTwoTarget)
            .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
            .addDep(libraryOne.getBuildTarget())
            .build();

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    TargetNode<?> parent =
        createJavaLibraryBuilder(parentTarget)
            .addSrc(Paths.get("java/src/com/parent/Meh.java"))
            .addDep(libraryTwo.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryOne, libraryTwo, parent);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    JavaLibrary libraryOneRule = (JavaLibrary) graphBuilder.requireRule(libraryOneTarget);
    JavaLibrary parentRule = (JavaLibrary) graphBuilder.requireRule(parentTarget);

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    Path root = libraryOneRule.getProjectFilesystem().getRootPath();
    assertEquals(
        ImmutableSet.of(
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(libraryTwoTarget, filesystem)),
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(parentTarget, filesystem))),
        resolve(parentRule.getTransitiveClasspaths(), pathResolver));
  }

  @Test
  public void testGetClasspathDeps() {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?> libraryOne =
        createJavaLibraryBuilder(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java"))
            .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?> libraryTwo =
        createJavaLibraryBuilder(libraryTwoTarget)
            .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
            .addDep(libraryOne.getBuildTarget())
            .build();

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    TargetNode<?> parent =
        createJavaLibraryBuilder(parentTarget)
            .addSrc(Paths.get("java/src/com/parent/Meh.java"))
            .addDep(libraryTwo.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryOne, libraryTwo, parent);
    graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule libraryOneRule = graphBuilder.requireRule(libraryOneTarget);
    BuildRule libraryTwoRule = graphBuilder.requireRule(libraryTwoTarget);
    BuildRule parentRule = graphBuilder.requireRule(parentTarget);

    assertThat(
        ((HasClasspathEntries) parentRule).getTransitiveClasspathDeps(),
        equalTo(
            ImmutableSet.of(
                getJavaLibrary(libraryOneRule),
                getJavaLibrary(libraryTwoRule),
                getJavaLibrary(parentRule))));
  }

  @Test
  public void testClasspathForJavacCommand() {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?> libraryOne =
        createJavaLibraryBuilder(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java"))
            .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?> libraryTwo =
        createJavaLibraryBuilder(libraryTwoTarget)
            .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
            .addDep(libraryOne.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(libraryOne, libraryTwo);
    graphBuilder = new TestActionGraphBuilder(targetGraph);

    DefaultJavaLibrary libraryOneRule =
        (DefaultJavaLibrary) graphBuilder.requireRule(libraryOneTarget);
    DefaultJavaLibrary libraryTwoRule =
        (DefaultJavaLibrary) graphBuilder.requireRule(libraryTwoTarget);

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    List<Step> steps =
        libraryTwoRule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    ImmutableList<JavacStep> javacSteps =
        FluentIterable.from(steps).filter(JavacStep.class).toList();
    assertEquals("There should be only one javac step.", 1, javacSteps.size());
    JavacStep javacStep = javacSteps.get(0);
    BuildRule expectedRule;
    if (compileAgainstAbis.equals(TRUE)) {
      expectedRule = graphBuilder.getRule(libraryOneRule.getAbiJar().get());
    } else {
      expectedRule = libraryOneRule;
    }
    String expectedName = expectedRule.getFullyQualifiedName();
    assertEquals(
        "The classpath for the javac step to compile //:libtwo should contain only " + expectedName,
        ImmutableSet.of(pathResolver.getAbsolutePath(expectedRule.getSourcePathToOutput())),
        javacStep.getClasspathEntries());
  }

  @Test
  public void testDepFilePredicateForNoAnnotationProcessorDeps() {
    BuildTarget annotationProcessorTarget = validJavaLibrary.createTarget();
    BuildTarget annotationProcessorAbiTarget = validJavaLibraryAbi.createTarget();

    validJavaLibrary.createRule(annotationProcessorTarget);
    BuildRule annotationProcessorAbiRule =
        validJavaLibraryAbi.createRule(annotationProcessorAbiTarget);

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libone");

    DefaultJavaLibrary libraryTwo =
        createJavaLibraryBuilder(libraryTwoTarget)
            .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
            .addAnnotationProcessorDep(annotationProcessorTarget)
            .build(graphBuilder);
    SourcePath sourcePath = annotationProcessorAbiRule.getSourcePathToOutput();
    assertFalse(
        "The predicate for dep file shouldn't contain annotation processor deps",
        libraryTwo
            .getCoveredByDepFilePredicate(
                DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)))
            .test(sourcePath));
  }

  /** Verify adding an annotation processor java binary with options. */
  @Test
  public void testAddAnnotationProcessorWithOptions() throws Exception {
    AnnotationProcessingScenario scenario = new AnnotationProcessingScenario();
    scenario.addAnnotationProcessorTarget(validJavaBinary);

    scenario
        .getAnnotationProcessingParamsBuilder()
        .setLegacyAnnotationProcessorNames(ImmutableList.of("MyProcessor"));
    scenario.getAnnotationProcessingParamsBuilder().addParameters("MyParameter");
    scenario.getAnnotationProcessingParamsBuilder().addParameters("MyKey=MyValue");
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
  public void testBuildDeps() {
    BuildTarget sourceDepExportFileTarget = BuildTargetFactory.newInstance("//:source_dep");
    TargetNode<?> sourceDepExportFileNode =
        new ExportFileBuilder(sourceDepExportFileTarget).build();

    BuildTarget depExportFileTarget = BuildTargetFactory.newInstance("//:dep_file");
    TargetNode<?> depExportFileNode = new ExportFileBuilder(depExportFileTarget).build();

    BuildTarget depLibraryExportedDepTarget =
        BuildTargetFactory.newInstance("//:dep_library_exported_dep");
    TargetNode<?> depLibraryExportedDepNode =
        createJavaLibraryBuilder(depLibraryExportedDepTarget)
            .addSrc(Paths.get("DepExportedDep.java"))
            .build();
    BuildTarget depLibraryTarget = BuildTargetFactory.newInstance("//:dep_library");
    TargetNode<?> depLibraryNode =
        createJavaLibraryBuilder(depLibraryTarget)
            .addSrc(Paths.get("Dep.java"))
            .addExportedDep(depLibraryExportedDepTarget)
            .build();
    BuildTarget depProvidedDepLibraryTarget =
        BuildTargetFactory.newInstance("//:dep_provided_dep_library");
    TargetNode<?> depProvidedDepLibraryNode =
        createJavaLibraryBuilder(depProvidedDepLibraryTarget)
            .addSrc(Paths.get("DepProvidedDep.java"))
            .build();

    BuildTarget exportedDepLibraryExportedDepTarget =
        BuildTargetFactory.newInstance("//:exported_dep_library_exported_dep");
    TargetNode<?> exportedDepLibraryExportedDepNode =
        createJavaLibraryBuilder(exportedDepLibraryExportedDepTarget)
            .addSrc(Paths.get("ExportedDepExportedDep.java"))
            .build();
    BuildTarget exportedDepLibraryTarget =
        BuildTargetFactory.newInstance("//:exported_dep_library");
    TargetNode<?> exportedDepLibraryNode =
        createJavaLibraryBuilder(exportedDepLibraryTarget)
            .addSrc(Paths.get("ExportedDep.java"))
            .addExportedDep(exportedDepLibraryExportedDepTarget)
            .build();
    BuildTarget exportedProvidedDepLibraryTarget =
        BuildTargetFactory.newInstance("//:exported_provided_dep_library");
    TargetNode<?> exportedProvidedDepLibraryNode =
        createJavaLibraryBuilder(exportedProvidedDepLibraryTarget)
            .addSrc(Paths.get("ExportedProvidedDep.java"))
            .build();

    BuildTarget providedDepLibraryExportedDepTarget =
        BuildTargetFactory.newInstance("//:provided_dep_library_exported_dep");
    TargetNode<?> providedDepLibraryExportedDepNode =
        createJavaLibraryBuilder(providedDepLibraryExportedDepTarget)
            .addSrc(Paths.get("ProvidedDepExportedDep.java"))
            .build();
    BuildTarget providedDepLibraryTarget =
        BuildTargetFactory.newInstance("//:provided_dep_library");
    TargetNode<?> providedDepLibraryNode =
        createJavaLibraryBuilder(providedDepLibraryTarget)
            .addSrc(Paths.get("ProvidedDep.java"))
            .addExportedDep(providedDepLibraryExportedDepTarget)
            .build();

    BuildTarget resourceDepPrebuiltJarTarget = BuildTargetFactory.newInstance("//:resource_dep");
    TargetNode<?> resourceDepPrebuiltJarNode =
        PrebuiltJarBuilder.createBuilder(resourceDepPrebuiltJarTarget)
            .setBinaryJar(Paths.get("binary.jar"))
            .build();

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    TargetNode<?> libraryNode =
        createJavaLibraryBuilder(libraryTarget)
            .addSrc(DefaultBuildTargetSourcePath.of(sourceDepExportFileTarget))
            .addDep(depLibraryTarget)
            .addDep(depExportFileTarget)
            .addDep(depProvidedDepLibraryTarget)
            .addExportedDep(exportedDepLibraryTarget)
            .addExportedDep(exportedProvidedDepLibraryTarget)
            .addProvidedDep(providedDepLibraryTarget)
            .addProvidedDep(exportedProvidedDepLibraryTarget)
            .addProvidedDep(depProvidedDepLibraryTarget)
            .addResource(DefaultBuildTargetSourcePath.of(resourceDepPrebuiltJarTarget))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryNode,
            sourceDepExportFileNode,
            depExportFileNode,
            depLibraryNode,
            depProvidedDepLibraryNode,
            depLibraryExportedDepNode,
            exportedDepLibraryNode,
            exportedProvidedDepLibraryNode,
            exportedDepLibraryExportedDepNode,
            providedDepLibraryNode,
            providedDepLibraryExportedDepNode,
            resourceDepPrebuiltJarNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);

    DefaultJavaLibrary library = (DefaultJavaLibrary) graphBuilder.requireRule(libraryTarget);

    Set<BuildRule> expectedDeps = new TreeSet<>();
    addAbiAndMaybeFullJar(depLibraryTarget, expectedDeps);
    addAbiAndMaybeFullJar(depProvidedDepLibraryTarget, expectedDeps);
    expectedDeps.add(graphBuilder.getRule(depExportFileTarget));
    addAbiAndMaybeFullJar(depLibraryExportedDepTarget, expectedDeps);
    addAbiAndMaybeFullJar(providedDepLibraryTarget, expectedDeps);
    addAbiAndMaybeFullJar(providedDepLibraryExportedDepTarget, expectedDeps);
    addAbiAndMaybeFullJar(exportedDepLibraryTarget, expectedDeps);
    addAbiAndMaybeFullJar(exportedProvidedDepLibraryTarget, expectedDeps);
    addAbiAndMaybeFullJar(exportedDepLibraryExportedDepTarget, expectedDeps);
    expectedDeps.add(graphBuilder.getRule(sourceDepExportFileTarget));
    expectedDeps.add(graphBuilder.getRule(resourceDepPrebuiltJarTarget));

    assertThat("Build deps mismatch!", library.getBuildDeps(), equalTo(expectedDeps));
    assertThat(
        library.getExportedDeps(),
        equalTo(
            ImmutableSortedSet.of(
                graphBuilder.getRule(exportedDepLibraryTarget),
                graphBuilder.getRule(exportedProvidedDepLibraryTarget))));

    assertThat(
        library.getDepsForTransitiveClasspathEntries(),
        equalTo(
            ImmutableSet.of(
                graphBuilder.getRule(depLibraryTarget),
                graphBuilder.getRule(depProvidedDepLibraryTarget),
                graphBuilder.getRule(exportedProvidedDepLibraryTarget),
                graphBuilder.getRule(depExportFileTarget),
                graphBuilder.getRule(exportedDepLibraryTarget))));

    // In Java packageables, exported_dep overrides dep overrides provided_dep. In android
    // packageables, they are complementary (i.e. one can export provided deps by simply putting
    // the dep in both lists). This difference is probably accidental, but it's now depended upon
    // by at least a couple projects.
    assertThat(
        ImmutableSet.copyOf(library.getRequiredPackageables(graphBuilder)),
        equalTo(
            ImmutableSet.of(
                graphBuilder.getRule(depLibraryTarget),
                graphBuilder.getRule(exportedDepLibraryTarget))));
  }

  private void addAbiAndMaybeFullJar(BuildTarget target, Set<BuildRule> set) {
    BuildRule fullJar = graphBuilder.getRule(target);
    BuildRule abiJar = graphBuilder.requireRule(((HasJavaAbi) fullJar).getAbiJar().get());

    set.add(abiJar);
    if (compileAgainstAbis.equals(FALSE)) {
      set.add(fullJar);
    }
  }

  @Test
  public void testExportedDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget nonIncludedTarget = BuildTargetFactory.newInstance("//:not_included");
    TargetNode<?> notIncludedNode =
        createJavaLibraryBuilder(nonIncludedTarget)
            .addSrc(Paths.get("java/src/com/not_included/Raz.java"))
            .build();

    BuildTarget includedTarget = BuildTargetFactory.newInstance("//:included");
    TargetNode<?> includedNode =
        createJavaLibraryBuilder(includedTarget)
            .addSrc(Paths.get("java/src/com/included/Rofl.java"))
            .build();

    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    TargetNode<?> libraryOneNode =
        createJavaLibraryBuilder(libraryOneTarget)
            .addDep(notIncludedNode.getBuildTarget())
            .addDep(includedNode.getBuildTarget())
            .addExportedDep(includedNode.getBuildTarget())
            .addSrc(Paths.get("java/src/com/libone/Bar.java"))
            .build();

    BuildTarget libraryTwoTarget = BuildTargetFactory.newInstance("//:libtwo");
    TargetNode<?> libraryTwoNode =
        createJavaLibraryBuilder(libraryTwoTarget)
            .addSrc(Paths.get("java/src/com/libtwo/Foo.java"))
            .addDep(libraryOneNode.getBuildTarget())
            .addExportedDep(libraryOneNode.getBuildTarget())
            .build();

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//:parent");
    TargetNode<?> parentNode =
        createJavaLibraryBuilder(parentTarget)
            .addSrc(Paths.get("java/src/com/parent/Meh.java"))
            .addDep(libraryTwoNode.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            notIncludedNode, includedNode, libraryOneNode, libraryTwoNode, parentNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuildRule notIncluded = graphBuilder.requireRule(notIncludedNode.getBuildTarget());
    BuildRule included = graphBuilder.requireRule(includedNode.getBuildTarget());
    BuildRule libraryOne = graphBuilder.requireRule(libraryOneTarget);
    BuildRule libraryTwo = graphBuilder.requireRule(libraryTwoTarget);
    BuildRule parent = graphBuilder.requireRule(parentTarget);

    Path root = parent.getProjectFilesystem().getRootPath();
    assertEquals(
        "A java_library that depends on //:libone should include only libone.jar in its "
            + "classpath when compiling itself.",
        ImmutableSet.of(
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(nonIncludedTarget, filesystem))),
        resolve(getJavaLibrary(notIncluded).getOutputClasspaths(), pathResolver));

    assertEquals(
        ImmutableSet.of(
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(includedTarget, filesystem))),
        resolve(getJavaLibrary(included).getOutputClasspaths(), pathResolver));

    assertEquals(
        ImmutableSet.of(
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(includedTarget, filesystem)),
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(includedTarget, filesystem))),
        resolve(getJavaLibrary(libraryOne).getOutputClasspaths(), pathResolver));

    assertEquals(
        "//:libtwo exports its deps, so a java_library that depends on //:libtwo should include "
            + "both libone.jar and libtwo.jar in its classpath when compiling itself.",
        ImmutableSet.of(
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(includedTarget, filesystem)),
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(libraryOneTarget, filesystem)),
            root.resolve(
                AbstractCompilerOutputPaths.getOutputJarPath(libraryTwoTarget, filesystem)),
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(includedTarget, filesystem))),
        resolve(getJavaLibrary(libraryTwo).getOutputClasspaths(), pathResolver));

    assertEquals(
        "A java_binary that depends on //:parent should include libone.jar, libtwo.jar and "
            + "parent.jar.",
        ImmutableSet.<Path>builder()
            .add(
                root.resolve(
                    AbstractCompilerOutputPaths.getOutputJarPath(includedTarget, filesystem)),
                root.resolve(
                    AbstractCompilerOutputPaths.getOutputJarPath(nonIncludedTarget, filesystem)),
                root.resolve(
                    AbstractCompilerOutputPaths.getOutputJarPath(libraryOneTarget, filesystem)),
                root.resolve(
                    AbstractCompilerOutputPaths.getOutputJarPath(libraryTwoTarget, filesystem)),
                root.resolve(
                    AbstractCompilerOutputPaths.getOutputJarPath(parentTarget, filesystem)))
            .build(),
        resolve(getJavaLibrary(parent).getTransitiveClasspaths(), pathResolver));

    assertThat(
        getJavaLibrary(parent).getTransitiveClasspathDeps(),
        equalTo(
            ImmutableSet.<JavaLibrary>builder()
                .add(getJavaLibrary(included))
                .add(getJavaLibrary(notIncluded))
                .add(getJavaLibrary(libraryOne))
                .add(getJavaLibrary(libraryTwo))
                .add(getJavaLibrary(parent))
                .build()));

    assertEquals(
        "A java_library that depends on //:parent should include only parent.jar in its "
            + "-classpath when compiling itself.",
        ImmutableSet.of(
            root.resolve(AbstractCompilerOutputPaths.getOutputJarPath(parentTarget, filesystem))),
        resolve(getJavaLibrary(parent).getOutputClasspaths(), pathResolver));
  }

  /**
   * Tests that an error is thrown when non-java library rules are listed in the exported deps
   * parameter.
   */
  @Test
  public void testExportedDepsShouldOnlyContainJavaLibraryRules() {
    BuildTarget genruleBuildTarget = BuildTargetFactory.newInstance("//generated:stuff");
    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(genruleBuildTarget)
            .setBash("echo 'aha' > $OUT")
            .setOut("stuff.txt")
            .build(graphBuilder);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    try {
      createDefaultJavaLibraryRuleWithAbiKey(
          buildTarget,
          /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
          /* deps */ ImmutableSortedSet.of(),
          /* exportedDeps */ ImmutableSortedSet.of(genrule),
          /* spoolMode */ Optional.empty(),
          /* postprocessClassesCommands */ ImmutableList.of());
      fail("A non-java library listed as exported dep should have thrown.");
    } catch (HumanReadableException e) {
      String expected =
          buildTarget
              + ": exported dep "
              + genruleBuildTarget
              + " ("
              + genrule.getType()
              + ") "
              + "must be a type of java library.";
      assertEquals(expected, e.getMessage());
    }
  }

  @Test
  public void testStepsPresenceForForDirectJarSpooling() throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    DefaultJavaLibrary javaLibraryBuildRule =
        createDefaultJavaLibraryRuleWithAbiKey(
            buildTarget,
            /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
            /* deps */ ImmutableSortedSet.of(),
            /* exportedDeps */ ImmutableSortedSet.of(),
            Optional.of(AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR),
            /* postprocessClassesCommands */ ImmutableList.of());

    BuildContext buildContext = createBuildContext();

    ImmutableList<Step> steps =
        javaLibraryBuildRule.getBuildSteps(buildContext, new FakeBuildableContext());

    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JavacStep.class)));
    assertThat(steps, Matchers.not(Matchers.hasItem(Matchers.instanceOf(JarDirectoryStep.class))));
  }

  @Test
  public void testJavacDirectToJarStepIsNotPresentWhenPostprocessClassesCommandsPresent()
      throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    DefaultJavaLibrary javaLibraryBuildRule =
        createDefaultJavaLibraryRuleWithAbiKey(
            buildTarget,
            /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
            /* deps */ ImmutableSortedSet.of(),
            /* exportedDeps */ ImmutableSortedSet.of(),
            Optional.of(AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR),
            /* postprocessClassesCommands */ ImmutableList.of("process_class_files.py"));

    BuildContext buildContext = createBuildContext();

    ImmutableList<Step> steps =
        javaLibraryBuildRule.getBuildSteps(buildContext, new FakeBuildableContext());

    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JavacStep.class)));
    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JarDirectoryStep.class)));
  }

  @Test
  public void testStepsPresenceForIntermediateOutputToDiskSpooling()
      throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:lib");

    DefaultJavaLibrary javaLibraryBuildRule =
        createDefaultJavaLibraryRuleWithAbiKey(
            buildTarget,
            /* srcs */ ImmutableSortedSet.of("foo/Bar.java"),
            /* deps */ ImmutableSortedSet.of(),
            /* exportedDeps */ ImmutableSortedSet.of(),
            Optional.of(AbstractJavacOptions.SpoolMode.INTERMEDIATE_TO_DISK),
            /* postprocessClassesCommands */ ImmutableList.of());

    BuildContext buildContext = createBuildContext();

    ImmutableList<Step> steps =
        javaLibraryBuildRule.getBuildSteps(buildContext, new FakeBuildableContext());

    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JavacStep.class)));
    assertThat(steps, Matchers.hasItem(Matchers.instanceOf(JarDirectoryStep.class)));
  }

  /** Tests that input-based rule keys work properly with generated sources. */
  @Test
  public void testInputBasedRuleKeySourceChange() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Setup a Java library consuming a source generated by a genrule and grab its rule key.
    BuildRule genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something")
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath(
        "class Test {}", pathResolver.getRelativePath(genSrc.getSourcePathToOutput()));
    JavaLibrary library =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(genSrc.getSourcePathToOutput())
            .build(graphBuilder, filesystem);
    FileHashCache originalHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    InputBasedRuleKeyFactory factory =
        new TestInputBasedRuleKeyFactory(originalHashCache, pathResolver, ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the genrule such that its rule key changes, but it's output stays the same (since
    // we don't change it).  This should *not* affect the input-based rule key of the consuming
    // java library, since it only cares about the contents of the source.
    graphBuilder = new TestActionGraphBuilder();
    genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something else")
            .build(graphBuilder, filesystem);
    library =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(genSrc.getSourcePathToOutput())
            .build(graphBuilder, filesystem);
    FileHashCache unaffectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(unaffectedHashCache, pathResolver, ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually modify the source, which should make the input-based rule key change.
    graphBuilder = new TestActionGraphBuilder();
    genSrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_srcs"))
            .setOut("Test.java")
            .setCmd("something else")
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath(
        "class Test2 {}", pathResolver.getRelativePath(genSrc.getSourcePathToOutput()));
    library =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addSrc(genSrc.getSourcePathToOutput())
            .build(graphBuilder, filesystem);
    FileHashCache affectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(affectedHashCache, pathResolver, ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  /** Tests that input-based rule keys work properly with simple Java library deps. */
  @Test
  public void testInputBasedRuleKeyWithJavaLibraryDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep.
    TargetNode<JavaLibraryDescriptionArg> depNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), filesystem)
            .addSrc(Paths.get("Source.java"))
            .build();
    TargetNode<?> libraryNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:lib"), filesystem)
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, libraryNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    JavaLibrary dep = (JavaLibrary) graphBuilder.requireRule(depNode.getBuildTarget());
    JavaLibrary library = (JavaLibrary) graphBuilder.requireRule(libraryNode.getBuildTarget());

    filesystem.writeContentsToPath(
        "JAR contents", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            graphBuilder.requireRule(dep.getAbiJar().get()).getSourcePathToOutput()),
        "Source.class",
        "ABI JAR contents");
    FileHashCache originalHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    InputBasedRuleKeyFactory factory =
        new TestInputBasedRuleKeyFactory(originalHashCache, pathResolver, ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the Java library dependency such that its rule key changes, and change its JAR
    // contents, but keep its ABI JAR the same.  This should *not* affect the input-based rule key
    // of the consuming java library, since it only cares about the contents of the ABI JAR.
    depNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("Source.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build();
    targetGraph = TargetGraphFactory.newInstance(depNode, libraryNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    dep = (JavaLibrary) graphBuilder.requireRule(depNode.getBuildTarget());
    library = (JavaLibrary) graphBuilder.requireRule(libraryNode.getBuildTarget());

    filesystem.writeContentsToPath(
        "different JAR contents", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    FileHashCache unaffectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(unaffectedHashCache, pathResolver, ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually change the Java library dependency's ABI JAR.  This *should* affect the
    // input-based rule key of the consuming java library.
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    dep = (JavaLibrary) graphBuilder.requireRule(depNode.getBuildTarget());
    library = (JavaLibrary) graphBuilder.requireRule(libraryNode.getBuildTarget());

    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            graphBuilder.requireRule(dep.getAbiJar().get()).getSourcePathToOutput()),
        "Source.class",
        "changed ABI JAR contents");
    FileHashCache affectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(affectedHashCache, pathResolver, ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with a Java library dep exported by a
   * first-order dep.
   */
  @Test
  public void testInputBasedRuleKeyWithExportedDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    // Setup a Java library which builds against another Java library dep exporting another Java
    // library dep.

    TargetNode<JavaLibraryDescriptionArg> exportedDepNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .build();
    TargetNode<?> depNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:dep"), filesystem)
            .addExportedDep(exportedDepNode.getBuildTarget())
            .build();
    TargetNode<?> libraryNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:lib"), filesystem)
            .addDep(depNode.getBuildTarget())
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(exportedDepNode, depNode, libraryNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    JavaLibrary exportedDep =
        (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:edep"));
    JavaLibrary library =
        (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "JAR contents", pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            graphBuilder.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "ABI JAR contents");

    FileHashCache originalHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    InputBasedRuleKeyFactory factory =
        new TestInputBasedRuleKeyFactory(originalHashCache, pathResolver, ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the exported Java library dependency such that its rule key changes, and change
    // its JAR contents, but keep its ABI JAR the same.  This should *not* affect the input-based
    // rule key of the consuming java library, since it only cares about the contents of the ABI
    // JAR.
    exportedDepNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build();
    targetGraph = TargetGraphFactory.newInstance(exportedDepNode, depNode, libraryNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    exportedDep = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "different JAR contents",
        pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    FileHashCache unaffectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(unaffectedHashCache, pathResolver, ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually change the exproted Java library dependency's ABI JAR.  This *should* affect
    // the input-based rule key of the consuming java library.
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    exportedDep = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:lib"));

    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            graphBuilder.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "changed ABI JAR contents");
    FileHashCache affectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(affectedHashCache, pathResolver, ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  /**
   * Tests that input-based rule keys work properly with a Java library dep exported through
   * multiple Java library dependencies.
   */
  @Test
  public void testInputBasedRuleKeyWithRecursiveExportedDeps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup a Java library which builds against another Java library dep exporting another Java
    // library dep.
    TargetNode<JavaLibraryDescriptionArg> exportedDepNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .build();
    TargetNode<?> dep2Node =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:dep2"), filesystem)
            .addExportedDep(exportedDepNode.getBuildTarget())
            .build();
    TargetNode<?> dep1Node =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:dep1"), filesystem)
            .addExportedDep(dep2Node.getBuildTarget())
            .build();
    TargetNode<?> libraryNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:lib"), filesystem)
            .addDep(dep1Node.getBuildTarget())
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(exportedDepNode, dep2Node, dep1Node, libraryNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    JavaLibrary exportedDep =
        (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:edep"));
    JavaLibrary library =
        (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "JAR contents", pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            graphBuilder.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "ABI JAR contents");
    FileHashCache originalHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    InputBasedRuleKeyFactory factory =
        new TestInputBasedRuleKeyFactory(originalHashCache, pathResolver, ruleFinder);
    RuleKey originalRuleKey = factory.build(library);

    // Now change the exported Java library dependency such that its rule key changes, and change
    // its JAR contents, but keep its ABI JAR the same.  This should *not* affect the input-based
    // rule key of the consuming java library, since it only cares about the contents of the ABI
    // JAR.
    exportedDepNode =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//:edep"), filesystem)
            .addSrc(Paths.get("Source1.java"))
            .setResourcesRoot(Paths.get("some root that changes the rule key"))
            .build();
    targetGraph = TargetGraphFactory.newInstance(exportedDepNode, dep2Node, dep1Node, libraryNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    exportedDep = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:lib"));

    filesystem.writeContentsToPath(
        "different JAR contents",
        pathResolver.getRelativePath(exportedDep.getSourcePathToOutput()));
    FileHashCache unaffectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(unaffectedHashCache, pathResolver, ruleFinder);
    RuleKey unaffectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, equalTo(unaffectedRuleKey));

    // Now actually change the exproted Java library dependency's ABI JAR.  This *should* affect
    // the input-based rule key of the consuming java library.
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    exportedDep = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:edep"));
    library = (JavaLibrary) graphBuilder.requireRule(BuildTargetFactory.newInstance("//:lib"));

    writeAbiJar(
        filesystem,
        pathResolver.getRelativePath(
            graphBuilder.requireRule(exportedDep.getAbiJar().get()).getSourcePathToOutput()),
        "Source1.class",
        "changed ABI JAR contents");
    FileHashCache affectedHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    factory = new TestInputBasedRuleKeyFactory(affectedHashCache, pathResolver, ruleFinder);
    RuleKey affectedRuleKey = factory.build(library);
    assertThat(originalRuleKey, Matchers.not(equalTo(affectedRuleKey)));
  }

  private DefaultJavaLibrary createDefaultJavaLibraryRuleWithAbiKey(
      BuildTarget buildTarget,
      ImmutableSet<String> srcs,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSortedSet<BuildRule> exportedDeps,
      Optional<AbstractJavacOptions.SpoolMode> spoolMode,
      ImmutableList<String> postprocessClassesCommands)
      throws NoSuchBuildTargetException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ImmutableSortedSet<SourcePath> srcsAsPaths =
        FluentIterable.from(srcs)
            .transform(Paths::get)
            .transform(p -> (SourcePath) PathSourcePath.of(projectFilesystem, p))
            .toSortedSet(Ordering.natural());

    BuildRuleParams buildRuleParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.copyOf(deps));

    JavacOptions javacOptions =
        spoolMode.isPresent()
            ? JavacOptions.builder(DEFAULT_JAVAC_OPTIONS).setSpoolMode(spoolMode.get()).build()
            : DEFAULT_JAVAC_OPTIONS;

    JavaLibraryDeps.Builder depsBuilder = new JavaLibraryDeps.Builder(graphBuilder);
    exportedDeps
        .stream()
        .peek(graphBuilder::addToIndex)
        .map(BuildRule::getBuildTarget)
        .forEach(depsBuilder::addExportedDepTargets);

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultJavaLibrary defaultJavaLibrary =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget,
                filesystem,
                new ToolchainProviderBuilder().build(),
                buildRuleParams,
                graphBuilder,
                TestCellBuilder.createCellRoots(filesystem),
                new JavaConfiguredCompilerFactory(
                    testJavaBuckConfig, JavacFactoryHelper.createJavacFactory(testJavaBuckConfig)),
                testJavaBuckConfig,
                null)
            .setJavacOptions(javacOptions)
            .setSrcs(srcsAsPaths)
            .setPostprocessClassesCommands(postprocessClassesCommands)
            .setDeps(depsBuilder.build())
            .build()
            .buildLibrary();

    graphBuilder.addToIndex(defaultJavaLibrary);
    return defaultJavaLibrary;
  }

  @Test
  public void testRuleKeyIsOrderInsensitiveForSourcesAndResources() {
    // Note that these filenames were deliberately chosen to have identical hashes to maximize
    // the chance of order-sensitivity when being inserted into a HashMap.  Just using
    // {foo,bar}.{java,txt} resulted in a passing test even for the old broken code.

    ProjectFilesystem filesystem =
        new AllExistingProjectFilesystem() {
          @Override
          public boolean isDirectory(Path path, LinkOption... linkOptionsk) {
            return false;
          }
        };
    ActionGraphBuilder graphBuilder1 = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder1 = new SourcePathRuleFinder(graphBuilder1);
    SourcePathResolver pathResolver1 = DefaultSourcePathResolver.from(ruleFinder1);
    DefaultJavaLibrary rule1 =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//lib:lib"))
            .addSrc(Paths.get("agifhbkjdec.java"))
            .addSrc(Paths.get("bdeafhkgcji.java"))
            .addSrc(Paths.get("bdehgaifjkc.java"))
            .addSrc(Paths.get("cfiabkjehgd.java"))
            .addResource(FakeSourcePath.of("becgkaifhjd.txt"))
            .addResource(FakeSourcePath.of("bkhajdifcge.txt"))
            .addResource(FakeSourcePath.of("cabfghjekid.txt"))
            .addResource(FakeSourcePath.of("chkdbafijge.txt"))
            .build(graphBuilder1, filesystem);

    ActionGraphBuilder graphBuilder2 = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder2 = new SourcePathRuleFinder(graphBuilder2);
    SourcePathResolver pathResolver2 = DefaultSourcePathResolver.from(ruleFinder2);
    DefaultJavaLibrary rule2 =
        createJavaLibraryBuilder(BuildTargetFactory.newInstance("//lib:lib"))
            .addSrc(Paths.get("cfiabkjehgd.java"))
            .addSrc(Paths.get("bdehgaifjkc.java"))
            .addSrc(Paths.get("bdeafhkgcji.java"))
            .addSrc(Paths.get("agifhbkjdec.java"))
            .addResource(FakeSourcePath.of("chkdbafijge.txt"))
            .addResource(FakeSourcePath.of("cabfghjekid.txt"))
            .addResource(FakeSourcePath.of("bkhajdifcge.txt"))
            .addResource(FakeSourcePath.of("becgkaifhjd.txt"))
            .build(graphBuilder2, filesystem);

    ImmutableMap.Builder<String, String> fileHashes = ImmutableMap.builder();
    for (String filename :
        ImmutableList.of(
            "agifhbkjdec.java",
            "bdeafhkgcji.java",
            "bdehgaifjkc.java",
            "cfiabkjehgd.java",
            "becgkaifhjd.txt",
            "bkhajdifcge.txt",
            "cabfghjekid.txt",
            "chkdbafijge.txt")) {
      fileHashes.put(filename, Hashing.sha1().hashString(filename, Charsets.UTF_8).toString());
    }
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            FakeFileHashCache.createFromStrings(fileHashes.build()), pathResolver1, ruleFinder1);
    DefaultRuleKeyFactory ruleKeyFactory2 =
        new TestDefaultRuleKeyFactory(
            FakeFileHashCache.createFromStrings(fileHashes.build()), pathResolver2, ruleFinder2);

    RuleKey key1 = ruleKeyFactory.build(rule1);
    RuleKey key2 = ruleKeyFactory2.build(rule2);
    assertEquals(key1, key2);
  }

  @Test
  public void testWhenNoJavacIsProvidedAJavacInMemoryStepIsAdded() {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildRule rule =
        createJavaLibraryBuilder(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java"))
            .build(graphBuilder);
    DefaultJavaLibrary buildRule = (DefaultJavaLibrary) rule;
    ImmutableList<Step> steps =
        buildRule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(
                DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder))),
            new FakeBuildableContext());

    assertEquals(17, steps.size());
    JavacStep javac = getJavacStep(steps);
    assertTrue(javac.getJavac() instanceof Jsr199Javac);
  }

  @Test
  public void testWhenJavacJarIsProvidedAJavacInMemoryStepIsAdded() {
    BuildTarget libraryOneTarget = BuildTargetFactory.newInstance("//:libone");
    BuildTarget javacTarget = BuildTargetFactory.newInstance("//langtools:javac");
    TargetNode<?> javacNode =
        PrebuiltJarBuilder.createBuilder(javacTarget)
            .setBinaryJar(Paths.get("java/src/com/libone/JavacJar.jar"))
            .build();
    TargetNode<?> ruleNode =
        createJavaLibraryBuilder(libraryOneTarget)
            .addSrc(Paths.get("java/src/com/libone/Bar.java"))
            .setCompiler(DefaultBuildTargetSourcePath.of(javacTarget))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(javacNode, ruleNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuildRule javac = graphBuilder.requireRule(javacTarget);
    BuildRule rule = graphBuilder.requireRule(libraryOneTarget);

    DefaultJavaLibrary buildable = (DefaultJavaLibrary) rule;
    ImmutableList<Step> steps =
        buildable.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(
                DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder))),
            new FakeBuildableContext());
    assertEquals(17, steps.size());
    JavacStep javacStep = getJavacStep(steps);
    assertTrue(javacStep.getJavac() instanceof Jsr199Javac);
    JarBackedJavac jsrJavac = ((JarBackedJavac) javacStep.getJavac());
    assertEquals(
        RichStream.from(jsrJavac.getCompilerClassPath())
            .map(pathResolver::getRelativePath)
            .collect(ImmutableSet.toImmutableSet()),
        ImmutableSet.of(pathResolver.getRelativePath(javac.getSourcePathToOutput())));
  }

  // Utilities
  private JavaLibrary getJavaLibrary(BuildRule rule) {
    return (JavaLibrary) rule;
  }

  public JavacStep getJavacStep(Iterable<Step> steps) {
    Step step = Iterables.find(steps, command -> command instanceof JavacStep);
    assertNotNull("Expected a JavacStep in the steplist.", step);
    return (JavacStep) step;
  }

  private JavaLibraryBuilder createJavaLibraryBuilder(BuildTarget target) {
    return JavaLibraryBuilder.createBuilder(target, testJavaBuckConfig);
  }

  private JavaLibraryBuilder createJavaLibraryBuilder(
      BuildTarget target, ProjectFilesystem projectFilesystem) {
    return JavaLibraryBuilder.createBuilder(target, testJavaBuckConfig, projectFilesystem);
  }

  private void writeAbiJar(
      ProjectFilesystem filesystem, Path abiJarPath, String fileName, String fileContents)
      throws IOException {
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.setEntryHashingEnabled(true);
      jar.writeEntry(
          fileName, new ByteArrayInputStream(fileContents.getBytes(StandardCharsets.UTF_8)));
    }
  }

  // test.
  private BuildContext createBuildContext() {
    return FakeBuildContext.withSourcePathResolver(
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)));
  }

  private abstract static class AnnotationProcessorTarget {
    private final String targetName;

    private AnnotationProcessorTarget(String targetName) {
      this.targetName = targetName;
    }

    public BuildTarget createTarget() {
      return BuildTargetFactory.newInstance(targetName);
    }

    public abstract BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException;
  }

  private AnnotationProcessorTarget validPrebuiltJar =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/library:prebuilt-processors") {
        @Override
        public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
          return PrebuiltJarBuilder.createBuilder(target)
              .setBinaryJar(Paths.get("MyJar"))
              .build(graphBuilder);
        }
      };

  private AnnotationProcessorTarget validJavaBinary =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/annotations:custom-processors") {
        @Override
        public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
          return new JavaBinaryRuleBuilder(target)
              .setMainClass("com.facebook.Main")
              .build(graphBuilder);
        }
      };

  private AnnotationProcessorTarget validJavaLibrary =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/somejava:library") {
        @Override
        public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
          return JavaLibraryBuilder.createBuilder(target, testJavaBuckConfig)
              .addSrc(Paths.get("MyClass.java"))
              .setProguardConfig(FakeSourcePath.of("MyProguardConfig"))
              .build(graphBuilder);
        }
      };

  private AnnotationProcessorTarget validJavaLibraryAbi =
      new AnnotationProcessorTarget("//tools/java/src/com/facebook/somejava:library#class-abi") {
        @Override
        public BuildRule createRule(BuildTarget target) throws NoSuchBuildTargetException {
          return CalculateClassAbi.of(
              target,
              new SourcePathRuleFinder(graphBuilder),
              new FakeProjectFilesystem(),
              TestBuildRuleParams.create(),
              FakeSourcePath.of("java/src/com/facebook/somejava/library/library-abi.jar"));
        }
      };

  // Captures all the common code between the different annotation processing test scenarios.
  private class AnnotationProcessingScenario {
    private final AnnotationProcessingParams.Builder annotationProcessingParamsBuilder;

    public AnnotationProcessingScenario() {
      annotationProcessingParamsBuilder =
          AnnotationProcessingParams.builder()
              .setLegacySafeAnnotationProcessors(Collections.emptySet());
    }

    public AnnotationProcessingParams.Builder getAnnotationProcessingParamsBuilder() {
      return annotationProcessingParamsBuilder;
    }

    public void addAnnotationProcessorTarget(AnnotationProcessorTarget processor)
        throws NoSuchBuildTargetException {
      BuildTarget target = processor.createTarget();
      BuildRule rule = processor.createRule(target);

      annotationProcessingParamsBuilder.addLegacyAnnotationProcessorDeps(rule);
    }

    public ImmutableList<String> buildAndGetCompileParameters()
        throws IOException, NoSuchBuildTargetException {
      ProjectFilesystem projectFilesystem =
          TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath());
      DefaultJavaLibrary javaLibrary = createJavaLibraryRule(projectFilesystem);
      BuildContext buildContext = createBuildContext();
      List<Step> steps = javaLibrary.getBuildSteps(buildContext, new FakeBuildableContext());
      JavacStep javacCommand = lastJavacCommand(steps);

      ExecutionContext executionContext =
          TestExecutionContext.newBuilder()
              .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
              .setDebugEnabled(true)
              .build();

      return javacCommand.getOptions(
          executionContext, /* buildClasspathEntries */ ImmutableSortedSet.of());
    }

    private DefaultJavaLibrary createJavaLibraryRule(ProjectFilesystem projectFilesystem)
        throws IOException, NoSuchBuildTargetException {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(ANNOTATION_SCENARIO_TARGET);
      annotationProcessingParamsBuilder.setProjectFilesystem(projectFilesystem);

      tmp.newFolder("android", "java", "src", "com", "facebook");
      String src = "android/java/src/com/facebook/Main.java";
      tmp.newFile(src);

      AnnotationProcessingParams params = annotationProcessingParamsBuilder.build();
      JavacOptions options =
          JavacOptions.builder(DEFAULT_JAVAC_OPTIONS).setAnnotationProcessingParams(params).build();

      BuildRuleParams buildRuleParams = TestBuildRuleParams.create();

      DefaultJavaLibrary javaLibrary =
          DefaultJavaLibrary.rulesBuilder(
                  buildTarget,
                  projectFilesystem,
                  new ToolchainProviderBuilder().build(),
                  buildRuleParams,
                  graphBuilder,
                  TestCellBuilder.createCellRoots(projectFilesystem),
                  new JavaConfiguredCompilerFactory(
                      testJavaBuckConfig,
                      JavacFactoryHelper.createJavacFactory(testJavaBuckConfig)),
                  testJavaBuckConfig,
                  null)
              .setJavacOptions(options)
              .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of(src)))
              .setResources(ImmutableSortedSet.of())
              .setDeps(new JavaLibraryDeps.Builder(graphBuilder).build())
              .setProguardConfig(Optional.empty())
              .setPostprocessClassesCommands(ImmutableList.of())
              .setResourcesRoot(Optional.empty())
              .setManifestFile(Optional.empty())
              .setMavenCoords(Optional.empty())
              .setTests(ImmutableSortedSet.of())
              .build()
              .buildLibrary();

      graphBuilder.addToIndex(javaLibrary);
      return javaLibrary;
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

  private static ImmutableSet<Path> resolve(
      ImmutableSet<SourcePath> paths, SourcePathResolver resolver) {
    return paths.stream().map(resolver::getAbsolutePath).collect(ImmutableSet.toImmutableSet());
  }
}
