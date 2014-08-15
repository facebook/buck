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
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class AuditInputCommandTest {

  private TestConsole console;
  private AuditInputCommand auditInputCommand;

  @Before
  public void setUp() {
    console = new TestConsole();
    Repository repository = new TestRepositoryBuilder().build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    auditInputCommand = new AuditInputCommand(new CommandRunnerParams(
        console,
        repository,
        new FakeAndroidDirectoryResolver(),
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        BuckTestConstant.PYTHON_INTERPRETER,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        new ObjectMapper()));
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

    BuildRule rootRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:test-java-library"))
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .build(ruleResolver);

    JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-android-library"))
        .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
        .addSrc(Paths.get("src/com/facebook/AndroidLibraryTwo.java"))
        .addDep(rootRule)
        .build(ruleResolver);

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
    PartialGraph partialGraph = PartialGraphFactory.newInstance(actionGraph, buildTargets);

    auditInputCommand.printJsonInputs(partialGraph);
    assertEquals(EXPECTED_JSON, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

}
