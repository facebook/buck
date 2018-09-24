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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidResourceBuilder;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckEventBusForTests.CapturingConsoleEventListener;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeOutputStream;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TargetsCommandTest {

  private TestConsole console;
  private ProjectWorkspace workspace;
  private TargetsCommand targetsCommand;
  private CommandRunnerParams params;
  private ProjectFilesystem filesystem;
  private ListeningExecutorService executor;
  private CapturingConsoleEventListener capturingConsoleEventListener;

  private SortedSet<TargetNode<?>> buildTargetNodes(
      ProjectFilesystem filesystem, String buildTarget) {
    SortedSet<TargetNode<?>> buildRules = new TreeSet<>();
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), buildTarget);
    TargetNode<?> node = JavaLibraryBuilder.createBuilder(target).build();
    buildRules.add(node);
    return buildRules;
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "target_command", tmp);
    workspace.setUp();

    filesystem =
        TestProjectFilesystems.createProjectFilesystem(
            workspace.getDestPath().toRealPath().normalize());
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    capturingConsoleEventListener = new CapturingConsoleEventListener();
    eventBus.register(capturingConsoleEventListener);

    targetsCommand = new TargetsCommand();
    params =
        CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
            console,
            cell,
            artifactCache,
            eventBus,
            FakeBuckConfig.builder().build(),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv()),
            new FakeJavaPackageFinder(),
            Optional.empty());
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void testJsonOutputForBuildTarget() throws IOException, BuildFileParseException {
    // run `buck targets` on the build file and parse the observed JSON.
    SortedSet<TargetNode<?>> nodes = buildTargetNodes(filesystem, "//:test-library");

    targetsCommand.printJsonForTargets(
        params, executor, nodes, ImmutableMap.of(), ImmutableSet.of());
    String observedOutput = console.getTextWrittenToStdOut();
    JsonNode observed = ObjectMappers.READER.readTree(ObjectMappers.createParser(observedOutput));

    // parse the expected JSON.
    String expectedJson = workspace.getFileContents("TargetsCommandTestBuckJson1.js");
    JsonNode expected =
        ObjectMappers.READER.readTree(
            ObjectMappers.createParser(expectedJson).enable(Feature.ALLOW_COMMENTS));

    assertEquals("Output from targets command should match expected JSON.", expected, observed);
    assertEquals("Nothing should be printed to stderr.", "", console.getTextWrittenToStdErr());
  }

  @Test
  public void testJsonOutputWithDirectDependencies() throws IOException {
    // Run Buck targets command on a case where the deps and direct_dependencies differ
    ProcessResult result = workspace.runBuckCommand("targets", "--json", "//:B");

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(
            ObjectMappers.createParser(result.getStdout()).enable(Feature.ALLOW_COMMENTS));

    // Parse the expected JSON.
    String expectedJson = workspace.getFileContents("TargetsCommandTestBuckJson2.js");
    JsonNode expected =
        ObjectMappers.READER.readTree(
            ObjectMappers.createParser(expectedJson).enable(Feature.ALLOW_COMMENTS));

    assertThat(
        "Output from targets command should match expected JSON.", observed, is(equalTo(expected)));
    assertThat(
        "Nothing should be printed to stderr.", console.getTextWrittenToStdErr(), is(equalTo("")));
  }

  @Test
  public void testJsonOutputWithOutputAttributes() throws IOException {
    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "--json",
            "//:B",
            "--output-attributes",
            "buck.direct_dependencies",
            "fully_qualified_name");

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(
            ObjectMappers.createParser(result.getStdout()).enable(Feature.ALLOW_COMMENTS));

    // Parse the expected JSON.
    String expectedJson = workspace.getFileContents("TargetsCommandTestBuckJson2Filtered.js");
    JsonNode expected =
        ObjectMappers.READER.readTree(
            ObjectMappers.createParser(expectedJson).enable(Feature.ALLOW_COMMENTS));

    assertThat(
        "Output from targets command should match expected JSON.", observed, is(equalTo(expected)));
    assertThat(
        "Nothing should be printed to stderr.", console.getTextWrittenToStdErr(), is(equalTo("")));
  }

  @Test
  public void testJsonOutputForMissingBuildTarget() throws BuildFileParseException {
    // nonexistent target should not exist.
    SortedSet<TargetNode<?>> buildRules = buildTargetNodes(filesystem, "//:nonexistent");
    targetsCommand.printJsonForTargets(
        params, executor, buildRules, ImmutableMap.of(), ImmutableSet.of());

    String output = console.getTextWrittenToStdOut();
    assertEquals("[" + System.lineSeparator() + "]" + System.lineSeparator(), output);
    assertThat(
        capturingConsoleEventListener.getLogMessages(),
        contains("unable to find rule for target //:nonexistent"));
  }

  @Test
  public void testPrintNullDelimitedTargets() throws UnsupportedEncodingException {
    Iterable<String> targets = ImmutableList.of("//foo:bar", "//foo:baz");
    FakeOutputStream fakeStream = new FakeOutputStream();
    PrintStream printStream = new PrintStream(fakeStream);
    TargetsCommand.printNullDelimitedTargets(targets, printStream);
    printStream.flush();
    assertEquals("//foo:bar\0//foo:baz\0", fakeStream.toString(Charsets.UTF_8.name()));
  }

  @Test
  public void testGetMatchingBuildTargets() {
    BuildTarget prebuiltJarTarget = BuildTargetFactory.newInstance("//empty:empty");
    TargetNode<?> prebuiltJarNode =
        PrebuiltJarBuilder.createBuilder(prebuiltJarTarget)
            .setBinaryJar(Paths.get("spoof"))
            .build();

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//javasrc:java-library");
    TargetNode<?> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("javasrc/JavaLibrary.java"))
            .addDep(prebuiltJarTarget)
            .build();

    BuildTarget javaTestTarget = BuildTargetFactory.newInstance("//javatest:test-java-library");
    TargetNode<?> javaTestNode =
        JavaTestBuilder.createBuilder(javaTestTarget)
            .addSrc(Paths.get("javatest/TestJavaLibrary.java"))
            .addDep(javaLibraryTarget)
            .build();

    ImmutableSet<TargetNode<?>> nodes =
        ImmutableSet.of(prebuiltJarNode, javaLibraryNode, javaTestNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    ImmutableSet<Path> referencedFiles;

    // No target depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("excludesrc/CannotFind.java"));
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Only test-android-library target depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("javatest/TestJavaLibrary.java"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//javatest:test-java-library"), matchingBuildRules.keySet());

    // The test-android-library target indirectly depends on the referenced file,
    // while test-java-library target directly depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("javasrc/JavaLibrary.java"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Verify that BUCK files show up as referenced files.
    referencedFiles = ImmutableSet.of(Paths.get("javasrc/BUCK"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Output target only need to depend on one referenced file.
    referencedFiles =
        ImmutableSet.of(
            Paths.get("javatest/TestJavaLibrary.java"), Paths.get("othersrc/CannotFind.java"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//javatest:test-java-library"), matchingBuildRules.keySet());

    // If no referenced file, means this filter is disabled, we can find all targets.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph, Optional.empty(), Optional.empty(), Optional.empty(), false, "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library", "//empty:empty"),
        matchingBuildRules.keySet());

    // Specify java_test, java_library as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.empty(),
            Optional.empty(),
            Optional.of(ImmutableSet.of(JavaTestDescription.class, JavaLibraryDescription.class)),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Specify java_test, java_library, and a rule name as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.empty(),
            Optional.of(ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
            Optional.of(ImmutableSet.of(JavaTestDescription.class, JavaLibraryDescription.class)),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());

    // Only filter by BuildTarget
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.empty(),
            Optional.of(ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());

    // Filter by BuildTarget and Referenced Files
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("javatest/TestJavaLibrary.java"))),
            Optional.of(ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.<String>of(), matchingBuildRules.keySet());
  }

  @Test
  public void testGetMatchingAppleLibraryBuildTarget() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo/foo.m"))))
            .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(libraryNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    // No target depends on the referenced file.
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/bar.m"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // The AppleLibrary matches the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/foo.m"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//foo:lib"), matchingBuildRules.keySet());
  }

  @Test
  public void testGetMatchingAppleTestBuildTarget() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo/foo.m"))))
            .build();

    BuildTarget testTarget = BuildTargetFactory.newInstance("//foo:xctest");
    TargetNode<?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo/testfoo.m"))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(libraryNode, testNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    // No target depends on the referenced file.
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/bar.m"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Both AppleLibrary nodes, AppleBundle, and AppleTest match the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/foo.m"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//foo:lib", "//foo:xctest"), matchingBuildRules.keySet());

    // The test AppleLibrary, AppleBundle and AppleTest match the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/testfoo.m"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of("//foo:xctest"), matchingBuildRules.keySet());
  }

  @Test
  public void testPathsUnderDirectories() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path resDir = Paths.get("some/resources/dir");
    BuildTarget androidResourceTarget = BuildTargetFactory.newInstance("//:res");
    TargetNode<?> androidResourceNode =
        AndroidResourceBuilder.createBuilder(androidResourceTarget).setRes(resDir).build();

    Path genSrc = resDir.resolve("foo.txt");
    BuildTarget genTarget = BuildTargetFactory.newInstance("//:gen");
    TargetNode<?> genNode =
        GenruleBuilder.newGenruleBuilder(genTarget)
            .setSrcs(ImmutableList.of(FakeSourcePath.of(projectFilesystem, genSrc)))
            .setOut("out")
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(androidResourceNode, genNode);

    SortedMap<String, TargetNode<?>> matchingBuildRules;

    // Specifying a resource under the resource directory causes a match.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(resDir.resolve("some_resource.txt"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(ImmutableSet.of(androidResourceTarget.toString()), matchingBuildRules.keySet());

    // Specifying a resource with the same string-like common prefix, but not under the above
    // resource dir, should not trigger a match.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get(resDir + "_extra").resolve("some_resource.txt"))),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Specifying a resource with the same string-like common prefix, but not under the above
    // resource dir, should not trigger a match.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(genSrc)),
            Optional.empty(),
            Optional.empty(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of(androidResourceTarget.toString(), genTarget.toString()),
        matchingBuildRules.keySet());
  }

  @Test
  public void testDetectTestChanges() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    BuildTarget libraryTestTarget1 = BuildTargetFactory.newInstance("//foo:xctest1");
    BuildTarget libraryTestTarget2 = BuildTargetFactory.newInstance("//foo:xctest2");
    BuildTarget testLibraryTarget = BuildTargetFactory.newInstance("//testlib:testlib");
    BuildTarget testLibraryTestTarget = BuildTargetFactory.newInstance("//testlib:testlib-xctest");

    TargetNode<?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo/foo.m"))))
            .setTests(ImmutableSortedSet.of(libraryTestTarget1, libraryTestTarget2))
            .build();

    TargetNode<?> libraryTestNode1 =
        AppleTestBuilder.createBuilder(libraryTestTarget1)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo/testfoo1.m"))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    TargetNode<?> libraryTestNode2 =
        AppleTestBuilder.createBuilder(libraryTestTarget2)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo/testfoo2.m"))))
            .setDeps(ImmutableSortedSet.of(testLibraryTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    TargetNode<?> testLibraryNode =
        AppleLibraryBuilder.createBuilder(testLibraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("testlib/testlib.m"))))
            .setTests(ImmutableSortedSet.of(testLibraryTestTarget))
            .build();

    TargetNode<?> testLibraryTestNode =
        AppleTestBuilder.createBuilder(testLibraryTestTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("testlib/testlib-test.m"))))
            .setDeps(ImmutableSortedSet.of(testLibraryTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    ImmutableSet<TargetNode<?>> nodes =
        ImmutableSet.of(
            libraryNode, libraryTestNode1, libraryTestNode2, testLibraryNode, testLibraryTestNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    // No target depends on the referenced file.
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/bar.m"))),
            Optional.empty(),
            Optional.empty(),
            true,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Test1, test2 and the library depend on the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/testfoo1.m"))),
            Optional.empty(),
            Optional.empty(),
            true,
            "BUCK");
    assertEquals(ImmutableSet.of("//foo:lib", "//foo:xctest1"), matchingBuildRules.keySet());

    // Test1, test2 and the library depend on the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/testfoo2.m"))),
            Optional.empty(),
            Optional.empty(),
            true,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//foo:lib", "//foo:xctest1", "//foo:xctest2"),
        matchingBuildRules.keySet());

    // Library, test1, test2, test library and its test depend on the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("testlib/testlib.m"))),
            Optional.empty(),
            Optional.empty(),
            true,
            "BUCK");
    assertEquals(
        ImmutableSet.of(
            "//foo:lib",
            "//foo:xctest1",
            "//foo:xctest2",
            "//testlib:testlib",
            "//testlib:testlib-xctest"),
        matchingBuildRules.keySet());

    // Library, test1, test2, test library and its test depend on the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("testlib/testlib-test.m"))),
            Optional.empty(),
            Optional.empty(),
            true,
            "BUCK");
    assertEquals(
        ImmutableSet.of(
            "//foo:lib",
            "//foo:xctest1",
            "//foo:xctest2",
            "//testlib:testlib",
            "//testlib:testlib-xctest"),
        matchingBuildRules.keySet());
  }
}
