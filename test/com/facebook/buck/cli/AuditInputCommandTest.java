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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class AuditInputCommandTest {

  private final String projectRootPath = ".";
  private final File projectRoot = new File(projectRootPath);
  private TestConsole console;
  private AuditInputCommand auditInputCommand;

  @Before
  public void setUp() {
    console = new TestConsole();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectRoot);
    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.getDefault();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    auditInputCommand = new AuditInputCommand(new CommandRunnerParams(
        console,
        projectFilesystem,
        new FakeAndroidDirectoryResolver(),
        buildRuleTypes,
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        BuckTestConstant.PYTHON_INTERPRETER,
        Platform.detect()));
  }

  private static final String EXPECTED_JSON = Joiner.on("").join(
      "{",
      "\"//:test-android-library\":",
      "[",
      "\"src/com/facebook/AndroidLibraryTwo.java\",",
      "\"src/com/facebook/TestAndroidLibrary.java\"",
      "],",
      "\"//:test-java-library\":",
      "[",
      "\"src/com/facebook/TestJavaLibrary.java\"",
      "]",
      "}");

  @Test
  public void testJsonClassPathOutput() throws IOException {
    // Build a DependencyGraph of build rules manually.
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    ImmutableList<String> targets = ImmutableList.of(
        "//:test-android-library",
        "//:test-java-library");

    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//:test-java-library"))
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java")));
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//:test-android-library"))
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addSrc(Paths.get("src/com/facebook/AndroidLibraryTwo.java"))
            .addDep(BuildTargetFactory.newInstance("//:test-java-library")));

    List<BuildTarget> buildTargets = Lists.transform(targets, new Function<String, BuildTarget>() {
      @Override
      public BuildTarget apply(String target) {
        return BuildTargetFactory.newInstance(target);
      }
    });
    DependencyGraph dependencyGraph = RuleMap.createGraphFromBuildRules(ruleResolver);
    PartialGraph partialGraph = PartialGraphFactory.newInstance(dependencyGraph, buildTargets);

    auditInputCommand.printJsonInputs(partialGraph);
    assertEquals(EXPECTED_JSON, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

}
