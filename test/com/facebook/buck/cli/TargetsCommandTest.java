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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidResourceBuilder;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeOutputStream;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedMap;

public class TargetsCommandTest {

  private TestConsole console;
  private TargetsCommand targetsCommand;
  private ObjectMapper objectMapper;

  private SortedMap<String, TargetNode<?>> buildTargetNodes(String baseName, String name) {
    SortedMap<String, TargetNode<?>> buildRules = Maps.newTreeMap();
    BuildTarget buildTarget = BuildTarget.builder(baseName, name).build();
    TargetNode<?> node = JavaLibraryBuilder
        .createBuilder(buildTarget)
        .build();
    buildRules.put(buildTarget.getFullyQualifiedName(), node);
    return buildRules;
  }

  private String testDataPath(String fileName) {
    return TestDataHelper.getTestDataDirectory(this)
        .resolve("target_command")
        .resolve(fileName)
        .toString();
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();
    Repository repository = new TestRepositoryBuilder()
        .setFilesystem(new ProjectFilesystem(Paths.get(".")))
        .build();
    AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    objectMapper = new ObjectMapper();

    targetsCommand =
        new TargetsCommand(CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
            console,
            new FakeRepositoryFactory(),
            repository,
            androidDirectoryResolver,
            new InstanceArtifactCacheFactory(artifactCache),
            eventBus,
            new ParserConfig(new FakeBuckConfig()),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv()),
            new FakeJavaPackageFinder(),
            objectMapper));
  }

  @Test
  public void testJsonOutputForBuildTarget()
      throws IOException, BuildFileParseException, InterruptedException {
    final String testBuckFileJson1 = testDataPath("TargetsCommandTestBuckJson1.js");

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, TargetNode<?>> nodes = buildTargetNodes(
        "//" + testDataPath(""),
        "test-library");

    targetsCommand.printJsonForTargets(nodes, new ParserConfig(new FakeBuckConfig()));
    String observedOutput = console.getTextWrittenToStdOut();
    JsonNode observed = objectMapper.readTree(
        objectMapper.getJsonFactory().createJsonParser(observedOutput));

    // parse the expected JSON.
    String expectedJson = Files.toString(new File(testBuckFileJson1), Charsets.UTF_8);
    JsonNode expected = objectMapper.readTree(
        objectMapper.getJsonFactory().createJsonParser(expectedJson)
            .enable(Feature.ALLOW_COMMENTS));

    assertEquals("Output from targets command should match expected JSON.", expected, observed);
    assertEquals("Nothing should be printed to stderr.",
        "",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void testJsonOutputForMissingBuildTarget()
      throws BuildFileParseException, IOException, InterruptedException {
    // nonexistent target should not exist.
    SortedMap<String, TargetNode<?>> buildRules = buildTargetNodes("//", "nonexistent");
    targetsCommand.printJsonForTargets(buildRules, new ParserConfig(new FakeBuckConfig()));

    String output = console.getTextWrittenToStdOut();
    assertEquals("[\n]\n", output);
    assertEquals(
        "unable to find rule for target //:nonexistent\n",
        console.getTextWrittenToStdErr());
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
  public void testGetMatchingBuildTargets() throws CmdLineException, IOException {
    BuildTarget prebuiltJarTarget = BuildTargetFactory.newInstance("//empty:empty");
    TargetNode<?> prebuiltJarNode = PrebuiltJarBuilder
        .createBuilder(prebuiltJarTarget)
        .setBinaryJar(Paths.get("spoof"))
        .build();

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//javasrc:java-library");
    TargetNode<?> javaLibraryNode = JavaLibraryBuilder
        .createBuilder(javaLibraryTarget)
        .addSrc(Paths.get("javasrc/JavaLibrary.java"))
        .addDep(prebuiltJarTarget)
        .build();

    BuildTarget javaTestTarget = BuildTargetFactory.newInstance("//javatest:test-java-library");
    TargetNode<?> javaTestNode = JavaTestBuilder
        .createBuilder(javaTestTarget)
        .addSrc(Paths.get("javatest/TestJavaLibrary.java"))
        .addDep(javaLibraryTarget)
        .build();

    ImmutableSet<TargetNode<?>>nodes = ImmutableSet.of(
        prebuiltJarNode,
        javaLibraryNode,
        javaTestNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    ImmutableSet<Path> referencedFiles;

    // No target depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("excludesrc/CannotFind.java"));
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Only test-android-library target depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("javatest/TestJavaLibrary.java"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library"),
        matchingBuildRules.keySet());

    // The test-android-library target indirectly depends on the referenced file,
    // while test-java-library target directly depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("javasrc/JavaLibrary.java"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Verify that BUCK files show up as referenced_files.
    referencedFiles = ImmutableSet.of(
        Paths.get("javasrc/BUCK"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Output target only need to depend on one referenced file.
    referencedFiles = ImmutableSet.of(
        Paths.get("javatest/TestJavaLibrary.java"),
        Paths.get("othersrc/CannotFind.java"));
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(referencedFiles),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library"),
        matchingBuildRules.keySet());

    // If no referenced file, means this filter is disabled, we can find all targets.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.<ImmutableSet<Path>>absent(),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of(
            "//javatest:test-java-library",
            "//javasrc:java-library",
            "//empty:empty"),
        matchingBuildRules.keySet());

    // Specify java_test, java_library as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.<ImmutableSet<Path>>absent(),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.of(ImmutableSet.of(JavaTestDescription.TYPE, JavaLibraryDescription.TYPE)),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of(
            "//javatest:test-java-library",
            "//javasrc:java-library"),
        matchingBuildRules.keySet());


    // Specify java_test, java_library, and a rule name as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.<ImmutableSet<Path>>absent(),
            Optional.of(ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
            Optional.of(ImmutableSet.of(JavaTestDescription.TYPE, JavaLibraryDescription.TYPE)),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());

    // Only filter by BuildTarget
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.<ImmutableSet<Path>>absent(),
            Optional.of(ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());


    // Filter by BuildTarget and Referenced Files
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("javatest/TestJavaLibrary.java"))),
            Optional.of(ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.<String>of(), matchingBuildRules.keySet());

  }

  @Test
  public void testGetMatchingAppleLibraryBuildTarget() throws CmdLineException, IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo/foo.m")))))
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.<TargetNode<?>>of(libraryNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    // No target depends on the referenced file.
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/bar.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // The AppleLibrary matches the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/foo.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//foo:lib"),
        matchingBuildRules.keySet());
  }

  @Test
  public void testGetMatchingAppleTestBuildTarget() throws CmdLineException, IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo/foo.m")))))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo/testfoo.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(libraryNode, testNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    // No target depends on the referenced file.
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/bar.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Both AppleLibrary nodes, AppleBundle, and AppleTest match the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/foo.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//foo:lib", "//foo:xctest"),
        matchingBuildRules.keySet());

    // The test AppleLibrary, AppleBundle and AppleTest match the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/testfoo.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//foo:xctest"),
        matchingBuildRules.keySet());
  }

  @Test
  public void testPathsUnderDirectories() throws CmdLineException, IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path resDir = Paths.get("some/resources/dir");
    BuildTarget androidResourceTarget = BuildTargetFactory.newInstance("//:res");
    TargetNode<?> androidResourceNode = AndroidResourceBuilder.createBuilder(androidResourceTarget)
        .setRes(resDir)
        .build();

    Path genSrc = resDir.resolve("foo.txt");
    BuildTarget genTarget = BuildTargetFactory.newInstance("//:res");
    TargetNode<?> genNode = GenruleBuilder.newGenruleBuilder(genTarget)
        .setSrcs(ImmutableList.<SourcePath>of(new PathSourcePath(projectFilesystem, genSrc)))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(androidResourceNode, genNode);

    SortedMap<String, TargetNode<?>> matchingBuildRules;

    // Specifying a resource under the resource directory causes a match.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(resDir.resolve("some_resource.txt"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of(androidResourceTarget.toString()),
        matchingBuildRules.keySet());

    // Specifying a resource with the same string-like common prefix, but not under the above
    // resource dir, should not trigger a match.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(
                ImmutableSet.of(
                    Paths.get(resDir.toString() + "_extra").resolve("some_resource.txt"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Specifying a resource with the same string-like common prefix, but not under the above
    // resource dir, should not trigger a match.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(genSrc)),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            false,
            "BUCK");
    assertEquals(
        ImmutableSet.of(androidResourceTarget.toString(), genTarget.toString()),
        matchingBuildRules.keySet());
  }

  @Test
  public void testDetectTestChanges() throws CmdLineException, IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget libraryTestTarget1 = BuildTarget.builder("//foo", "xctest1").build();
    BuildTarget libraryTestTarget2 = BuildTarget.builder("//foo", "xctest2").build();
    BuildTarget testLibraryTarget = BuildTarget.builder("//testlib", "testlib").build();
    BuildTarget testLibraryTestTarget = BuildTarget.builder("//testlib", "testlib-xctest").build();

    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo/foo.m")))))
        .setTests(Optional.of(ImmutableSortedSet.of(libraryTestTarget1, libraryTestTarget2)))
        .build();

    TargetNode<?> libraryTestNode1 = AppleTestBuilder
        .createBuilder(libraryTestTarget1)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo/testfoo1.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    TargetNode<?> libraryTestNode2 = AppleTestBuilder
        .createBuilder(libraryTestTarget2)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo/testfoo2.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(testLibraryTarget)))
        .build();

    TargetNode<?> testLibraryNode = AppleLibraryBuilder
        .createBuilder(testLibraryTarget)
        .setSrcs(Optional.of(ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("testlib/testlib.m")))))
        .setTests(Optional.of(ImmutableSortedSet.of(testLibraryTestTarget)))
        .build();

    TargetNode<?> testLibraryTestNode = AppleTestBuilder
        .createBuilder(testLibraryTestTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("testlib/testlib-test.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(testLibraryTarget)))
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(
        libraryNode,
        libraryTestNode1,
        libraryTestNode2,
        testLibraryNode,
        testLibraryTestNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    // No target depends on the referenced file.
    SortedMap<String, TargetNode<?>> matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/bar.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            true,
            "BUCK");
    assertTrue(matchingBuildRules.isEmpty());

    // Test1, test2 and the library depend on the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/testfoo1.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
            true,
            "BUCK");
    assertEquals(
        ImmutableSet.of("//foo:lib", "//foo:xctest1"),
        matchingBuildRules.keySet());

    // Test1, test2 and the library depend on the referenced file.
    matchingBuildRules =
        targetsCommand.getMatchingNodes(
            targetGraph,
            Optional.of(ImmutableSet.of(Paths.get("foo/testfoo2.m"))),
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
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
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
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
            Optional.<ImmutableSet<BuildTarget>>absent(),
            Optional.<ImmutableSet<BuildRuleType>>absent(),
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
