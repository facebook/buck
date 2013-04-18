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

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.AndroidBinaryRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultJavaLibraryRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.JavaLibraryRule;
import com.facebook.buck.rules.JavaTestRule;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

public class AuditClasspathCommandTest {

  private final String projectRootPath = ".";
  private final File projectRoot = new File(projectRootPath);
  private final Ansi ansi = new Ansi(false);
  private CapturingPrintStream stdOutStream;
  private CapturingPrintStream stdErrStream;
  private AuditClasspathCommand auditClasspathCommand;

  @Before
  public void setup() {
    stdOutStream = new CapturingPrintStream();
    stdErrStream = new CapturingPrintStream();
    Console console = new Console(stdOutStream, stdErrStream, ansi);
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectRoot);
    auditClasspathCommand =
        new AuditClasspathCommand(stdOutStream, stdErrStream, console, projectFilesystem);
  }

  private String getCapturedOutput(CapturingPrintStream stream) {
    return stream.getContentsAsString(Charsets.UTF_8);
  }

  private PartialGraph createGraphFromBuildRules(Iterable<BuildRule> rules, List<String> targets) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();
    for (BuildRule rule : rules) {
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }

    List<BuildTarget> buildTargets = Lists.transform(targets, new Function<String, BuildTarget>() {
      @Override
      public BuildTarget apply(String target) {
        return BuildTargetFactory.newInstance(target);
      }
    });

    DependencyGraph dependencyGraph = new DependencyGraph(graph);
    return PartialGraphFactory.newInstance(dependencyGraph, buildTargets);
  }

  @Test
  public void testClassPathOutput() {
    // Build a DependencyGraph of build rules manually.
    Map<String, BuildRule> buildRuleIndex1 = Maps.newHashMap();
    List<String> targets = Lists.newArrayList();

    // Test that no output is created.
    PartialGraph partialGraph1 = createGraphFromBuildRules(buildRuleIndex1.values(), targets);
    auditClasspathCommand.printClasspath(partialGraph1);
    assertEquals("", getCapturedOutput(stdOutStream));
    assertEquals("", getCapturedOutput(stdErrStream));

    // Add build rules such that all implementations of HasClasspathEntries are tested.
    DefaultJavaLibraryRule javaLibraryRule = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:test-java-library"))
        .addSrc("src/com/facebook/TestJavaLibrary.java")
        .build(buildRuleIndex1);
    buildRuleIndex1.put(javaLibraryRule.getFullyQualifiedName(), javaLibraryRule);
    JavaLibraryRule androidLibraryRule =
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:test-android-library"))
        .addSrc("src/com/facebook/TestAndroidLibrary.java")
        .addDep("//:test-java-library")
        .build(buildRuleIndex1);
    buildRuleIndex1.put(androidLibraryRule.getFullyQualifiedName(), androidLibraryRule);
    AndroidBinaryRule androidBinaryRule = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:test-android-binary"))
        .setManifest("AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("keystore.properties")
        .addDep("//:test-android-library")
        .addDep("//:test-java-library")
        .build(buildRuleIndex1);
    buildRuleIndex1.put(androidBinaryRule.getFullyQualifiedName(), androidBinaryRule);
    JavaTestRule testRule = JavaTestRule.newJavaTestRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:project-tests"))
        .addDep("//:test-java-library")
        .setSourceUnderTest(ImmutableSet.of("//:test-java-library"))
        .addSrc("src/com/facebook/test/ProjectTests.java")
        .build(buildRuleIndex1);
    buildRuleIndex1.put(testRule.getFullyQualifiedName(), testRule);
    PartialGraph partialGraph2 = createGraphFromBuildRules(buildRuleIndex1.values(), targets);
    auditClasspathCommand.printClasspath(partialGraph2);

    // Still empty.
    assertEquals("", getCapturedOutput(stdOutStream));
    assertEquals("", getCapturedOutput(stdErrStream));

    // Request the top build target. This will test the following:
    // - paths don't appear multiple times when dependencies are referenced multiple times.
    // - dependencies are walked
    // - independent targets in the same BUCK file are not included in the output
    targets.add("//:test-android-binary");
    PartialGraph partialGraph3 = createGraphFromBuildRules(buildRuleIndex1.values(), targets);
    auditClasspathCommand.printClasspath(partialGraph3);

    SortedSet<String> expectedPaths = Sets.newTreeSet(
        Arrays.asList(
            GEN_DIR + "/lib__test-android-library__output/test-android-library.jar",
            GEN_DIR + "/lib__test-java-library__output/test-java-library.jar"
        )
    );
    String expectedClasspath = Joiner.on("\n").join(expectedPaths) + "\n";

    assertEquals(expectedClasspath, getCapturedOutput(stdOutStream));
    assertEquals("", getCapturedOutput(stdErrStream));

    // Add independent test target. This will test:
    // - the union of the classpath is output.
    // - all rules have implemented HasClasspathEntries.
    // Note that the output streams are reset.
    setup();
    targets.add("//:test-java-library");
    targets.add("//:test-android-library");
    targets.add("//:project-tests");
    PartialGraph partialGraph4 = createGraphFromBuildRules(buildRuleIndex1.values(), targets);
    auditClasspathCommand.printClasspath(partialGraph4);

    expectedPaths.add(GEN_DIR + "/lib__project-tests__output/project-tests.jar");
    expectedClasspath = Joiner.on("\n").join(expectedPaths) + "\n";
    assertEquals(expectedClasspath, getCapturedOutput(stdOutStream));
    assertEquals("", getCapturedOutput(stdErrStream));
  }
}
