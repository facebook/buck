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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.TargetsCommand.TargetsCommandPredicate;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
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

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedMap;

public class TargetsCommandTest {

  private TestConsole console;
  private TargetsCommand targetsCommand;
  private ObjectMapper objectMapper;

  private SortedMap<String, BuildRule> buildBuildTargets(String outputFile, String name) {
    return buildBuildTargets(outputFile, name, "//");
  }

  private SortedMap<String, BuildRule> buildBuildTargets(String outputFile,
      String name,
      String baseName) {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    SortedMap<String, BuildRule> buildRules = Maps.newTreeMap();
    BuildTarget buildTarget = BuildTarget.builder(baseName, name).build();
    FakeBuildRule buildRule = new FakeBuildRule(
        JavaLibraryDescription.TYPE,
        buildTarget,
        pathResolver,
        ImmutableSortedSet.<BuildRule>of()
    );
    buildRule.setOutputFile(outputFile);

    buildRules.put(buildTarget.getFullyQualifiedName(), buildRule);
    return buildRules;
  }

  private String testDataPath(String fileName) {
    return "testdata/com/facebook/buck/cli/" + fileName;
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
        new TargetsCommand(new CommandRunnerParams(
            console,
            new FakeRepositoryFactory(),
            repository,
            androidDirectoryResolver,
            new InstanceArtifactCacheFactory(artifactCache),
            eventBus,
            BuckTestConstant.PYTHON_INTERPRETER,
            BuckTestConstant.ALLOW_EMPTY_GLOBS,
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv()),
            new FakeJavaPackageFinder(),
            objectMapper,
            FakeFileHashCache.EMPTY_CACHE));
  }

  @Test
  public void testJsonOutputForBuildTarget()
      throws IOException, BuildFileParseException, InterruptedException {
    final String testBuckFileJson1 = testDataPath("TargetsCommandTestBuckJson1.js");
    final String outputFile = "buck-out/gen/test/outputFile";

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(
        outputFile, "test-library", "//testdata/com/facebook/buck/cli");

    targetsCommand.printJsonForTargets(buildRules, /* includes */ ImmutableList.<String>of());
    String observedOutput = console.getTextWrittenToStdOut();
    JsonNode observed = objectMapper.readTree(
        objectMapper.getJsonFactory().createJsonParser(observedOutput));

    // parse the expected JSON.
    String expectedJson = Files.toString(new File(testBuckFileJson1), Charsets.UTF_8)
        .replace("{$OUTPUT_FILE}", outputFile);
    JsonNode expected = objectMapper.readTree(
        objectMapper.getJsonFactory().createJsonParser(expectedJson)
            .enable(Feature.ALLOW_COMMENTS));

    assertEquals("Output from targets command should match expected JSON.", expected, observed);
    assertEquals("Nothing should be printed to stderr.",
        "",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void testNormalOutputForBuildTarget() throws IOException {
    final String outputFile = "buck-out/gen/test/outputFile";

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(outputFile, "test-library");

    targetsCommand.printTargetsList(buildRules, /* showOutput */ false, /* showRuleKey */ false);
    String observedOutput = console.getTextWrittenToStdOut();

    assertEquals("Output from targets command should match expected output.",
        "//:test-library",
        observedOutput.trim());
    assertEquals("Nothing should be printed to stderr.",
        "",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void testNormalOutputForBuildTargetWithOutput() throws IOException {
    final String outputFile = "buck-out/gen/test/outputFile";

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(
        outputFile,
        "test-library");

    targetsCommand.printTargetsList(buildRules, /* showOutput */ true, /* showRuleKey */ false);
    String observedOutput = console.getTextWrittenToStdOut();

    assertEquals("Output from targets command should match expected output.",
        "//:test-library " + outputFile,
        observedOutput.trim());
    assertEquals("Nothing should be printed to stderr.",
        "",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void testJsonOutputForMissingBuildTarget()
      throws BuildFileParseException, IOException, InterruptedException {
    // nonexistent target should not exist.
    final String outputFile = "buck-out/gen/test/outputFile";
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(outputFile, "nonexistent");
    targetsCommand.printJsonForTargets(buildRules, /* includes */ ImmutableList.<String>of());

    String output = console.getTextWrittenToStdOut();
    assertEquals("[\n]\n", output);
    assertEquals(
        "unable to find rule for target //:nonexistent\n",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void testValidateBuildTargetForNonAliasTarget()
      throws IOException, NoSuchBuildTargetException, InterruptedException {
    // Set up the test buck file, parser, config, options.
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse("//:test-library", ParseContext.fullyQualified()))
        .andReturn(BuildTarget.builder("//testdata/com/facebook/buck/cli", "test-library").build())
        .anyTimes();
    EasyMock.expect(parser.parse("//:", ParseContext.fullyQualified()))
        .andThrow(new BuildTargetParseException(
            String.format("%s cannot end with a colon.", "//:")))
        .anyTimes();
    EasyMock.expect(parser.parse("//blah/foo:bar", ParseContext.fullyQualified()))
        .andReturn(BuildTarget.builder("//blah/foo", "bar").build())
        .anyTimes();
    EasyMock.expect(parser.parse("//:test-libarry", ParseContext.fullyQualified()))
        .andReturn(BuildTarget.builder("//testdata/com/facebook/buck/cli", "test-libarry").build())
        .anyTimes();
    EasyMock.replay(parser);
    Reader reader = new StringReader("");
    BuckConfig config = BuckConfig.createFromReader(
        reader,
        new ProjectFilesystem(Paths.get(".")),
        parser,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
    TargetsCommandOptions options = new TargetsCommandOptions(config);

    // Test a valid target.
    assertEquals(
        "//testdata/com/facebook/buck/cli:test-library",
        targetsCommand.validateBuildTargetForFullyQualifiedTarget("//:test-library", options));

    // Targets that will be rejected by BuildTargetParser with an exception.
    try {
      targetsCommand.validateBuildTargetForFullyQualifiedTarget("//:", options);
      fail("Should have thrown BuildTargetParseException.");
    } catch (BuildTargetParseException e) {
      assertEquals("//: cannot end with a colon.", e.getHumanReadableErrorMessage());
    }
    assertNull(targetsCommand.validateBuildTargetForFullyQualifiedTarget(
        "//blah/foo:bar", options));

    // Should pass BuildTargetParser but validateBuildTargetForNonAliasTarget will return null.
    assertNull(targetsCommand.validateBuildTargetForFullyQualifiedTarget(
        "//:test-libarry", options));
  }

  @Test
  public void testGetMachingBuildTargets() throws CmdLineException, IOException {
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

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
    ActionGraph actionGraph = targetGraph.getActionGraph(eventBus);
    ImmutableSet<BuildRuleType> buildRuleTypes = ImmutableSet.of();

    ImmutableSet<Path> referencedFiles;

    // No target depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("excludesrc/CannotFind.java"));
    SortedMap<String, BuildRule> matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                buildRuleTypes,
                referencedFiles,
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertTrue(matchingBuildRules.isEmpty());

    // Only test-android-library target depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("javatest/TestJavaLibrary.java"));
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                buildRuleTypes,
                referencedFiles,
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library"),
        matchingBuildRules.keySet());

    // The test-android-library target indirectly depends on the referenced file,
    // while test-java-library target directly depends on the referenced file.
    referencedFiles = ImmutableSet.of(Paths.get("javasrc/JavaLibrary.java"));
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                buildRuleTypes,
                referencedFiles,
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Verify that BUCK files show up as referenced_files.
    referencedFiles = ImmutableSet.of(Paths.get("javasrc/" + BuckConstant.BUILD_RULES_FILE_NAME));
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                buildRuleTypes,
                referencedFiles,
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Output target only need to depend on one referenced file.
    referencedFiles = ImmutableSet.of(
        Paths.get("javatest/TestJavaLibrary.java"),
        Paths.get("othersrc/CannotFind.java"));
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                buildRuleTypes,
                referencedFiles,
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library"),
        matchingBuildRules.keySet());

    // If no referenced file, means this filter is disabled, we can find all targets.
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                buildRuleTypes,
                ImmutableSet.<Path>of(),
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertEquals(
        ImmutableSet.of(
            "//javatest:test-java-library",
            "//javasrc:java-library",
            "//empty:empty"),
        matchingBuildRules.keySet());

    // Specify java_test, java_library as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                ImmutableSet.of(JavaTestDescription.TYPE, JavaLibraryDescription.TYPE),
                ImmutableSet.<Path>of(),
                Optional.<ImmutableSet<BuildTarget>>absent(),
                eventBus));
    assertEquals(
        ImmutableSet.of(
            "//javatest:test-java-library",
            "//javasrc:java-library"),
        matchingBuildRules.keySet());


    // Specify java_test, java_library, and a rule name as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                ImmutableSet.of(JavaTestDescription.TYPE, JavaLibraryDescription.TYPE),
                ImmutableSet.<Path>of(),
                Optional.of(
                    ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
                eventBus));
    assertEquals(
        ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());

    // Only filter by BuildTarget
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                ImmutableSet.<BuildRuleType>of(),
                ImmutableSet.<Path>of(),
                Optional.of(
                    ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
                eventBus));
    assertEquals(
        ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());


    // Filter by BuildTarget and Referenced Files
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            actionGraph,
            new TargetsCommandPredicate(
                targetGraph,
                ImmutableSet.<BuildRuleType>of(),
                ImmutableSet.of(Paths.get("javatest/TestJavaLibrary.java")),
                Optional.of(
                    ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))),
                eventBus));
    assertEquals(
        ImmutableSet.<String>of(), matchingBuildRules.keySet());

  }
}
