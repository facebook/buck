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

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;

public class AuditClasspathCommandTest {

  private TestConsole console;
  private AuditClasspathCommand auditClasspathCommand;

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();
    AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    Repository repository = new TestRepositoryBuilder().build();

    auditClasspathCommand = new AuditClasspathCommand(new CommandRunnerParams(
        console,
        new FakeRepositoryFactory(),
        repository,
        androidDirectoryResolver,
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        BuckTestConstant.PYTHON_INTERPRETER,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        new ObjectMapper(),
        FakeFileHashCache.EMPTY_CACHE));
  }

  private PartialGraph createGraphFromBuildRules(BuildRuleResolver ruleResolver,
      List<String> targets) {
    ImmutableSet<BuildTarget> buildTargets = ImmutableSet.copyOf(
        Iterables.transform(
            targets,
            new Function<String, BuildTarget>() {
              @Override
              public BuildTarget apply(String target) {
                return BuildTargetFactory.newInstance(target);
              }
            }));

    ActionGraph actionGraph = RuleMap.createGraphFromBuildRules(ruleResolver);
    return PartialGraphFactory.newInstance(actionGraph, buildTargets);
  }

  @Test
  public void testClassPathOutput()
      throws IOException, InterruptedException {
    // Build a DependencyGraph of build rules manually.
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    List<String> targets = Lists.newArrayList();

    // Test that no output is created.
    PartialGraph partialGraph1 = createGraphFromBuildRules(ruleResolver, targets);
    auditClasspathCommand.printClasspath(partialGraph1);
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Add build rules such that all implementations of HasClasspathEntries are tested.
    BuildRule javaLibrary = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:test-java-library"))
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .build(ruleResolver);

    BuildRule androidLibrary = AndroidLibraryBuilder.createBuilder(
        BuildTargetFactory.newInstance(
            "//:test-android-library"))
        .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
        .addDep(javaLibrary.getBuildTarget())
        .build(ruleResolver);

    BuildTarget keystoreBuildTarget = BuildTargetFactory.newInstance("//:keystore");
    Keystore keystore = (Keystore) KeystoreBuilder.createBuilder(keystoreBuildTarget)
        .setStore(Paths.get("debug.keystore"))
        .setProperties(Paths.get("keystore.properties"))
        .build(ruleResolver);
    AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-android-binary"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystore.getBuildTarget())
        .setOriginalDeps(
            ImmutableSortedSet.of(androidLibrary.getBuildTarget(), javaLibrary.getBuildTarget()))
        .build(ruleResolver);
    JavaTestBuilder.newJavaTestBuilder(BuildTargetFactory.newInstance("//:project-tests"))
        .addDep(javaLibrary.getBuildTarget())
        .setSourceUnderTest(ImmutableSortedSet.of(javaLibrary.getBuildTarget()))
        .addSrc(Paths.get("src/com/facebook/test/ProjectTests.java"))
        .build(ruleResolver);
    PartialGraph partialGraph2 = createGraphFromBuildRules(ruleResolver, targets);
    auditClasspathCommand.printClasspath(partialGraph2);

    // Still empty.
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Request the top build target. This will test the following:
    // - paths don't appear multiple times when dependencies are referenced multiple times.
    // - dependencies are walked
    // - independent targets in the same BUCK file are not included in the output
    targets.add("//:test-android-binary");
    PartialGraph partialGraph3 = createGraphFromBuildRules(ruleResolver, targets);
    auditClasspathCommand.printClasspath(partialGraph3);

    SortedSet<String> expectedPaths = Sets.newTreeSet(
        Arrays.asList(
            GEN_DIR + "/lib__test-android-library__output/test-android-library.jar",
            GEN_DIR + "/lib__test-java-library__output/test-java-library.jar"));
    String expectedClasspath = Joiner.on("\n").join(expectedPaths) + "\n";

    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Add independent test target. This will test:
    // - the union of the classpath is output.
    // - all rules have implemented HasClasspathEntries.
    // Note that the output streams are reset.
    setUp();
    targets.add("//:test-java-library");
    targets.add("//:test-android-library");
    targets.add("//:project-tests");
    PartialGraph partialGraph4 = createGraphFromBuildRules(ruleResolver, targets);
    auditClasspathCommand.printClasspath(partialGraph4);

    expectedPaths.add(GEN_DIR + "/lib__project-tests__output/project-tests.jar");
    expectedClasspath = Joiner.on("\n").join(expectedPaths) + "\n";
    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  private static final String EXPECTED_JSON = Joiner.on("").join(
      "{",
      "\"//:test-android-library\":",
      "[",
      "\"buck-out/gen/lib__test-java-library__output/test-java-library.jar\",",
      "\"buck-out/gen/lib__test-android-library__output/test-android-library.jar\"",
      "],",
      "\"//:test-java-library\":",
      "[",
      "\"buck-out/gen/lib__test-java-library__output/test-java-library.jar\"",
      "]",
      "}");

  @Test
  public void testJsonClassPathOutput() throws IOException {
    // Build a DependencyGraph of build rules manually.
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    ImmutableList<String> targets = ImmutableList.of(
        "//:test-android-library",
        "//:test-java-library");

    BuildRule library = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:test-java-library"))
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .build(ruleResolver);
    AndroidLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-android-library"))
        .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
        .addDep(library.getBuildTarget())
        .build(ruleResolver);

    PartialGraph partialGraph = createGraphFromBuildRules(ruleResolver, targets);
    auditClasspathCommand.printJsonClasspath(partialGraph);

    assertEquals(EXPECTED_JSON, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

}
