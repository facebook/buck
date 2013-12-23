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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.command.Project;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.ProjectConfigRule;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

public class ProjectCommandTest {

  private static final ImmutableMap<String, Object> EMPTY_PARSE_DATA = ImmutableMap.of();
  private static final ArtifactCache artifactCache = new NoopArtifactCache();

  @Test
  public void testBasicProjectCommand() throws Exception {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget javaLibraryTargetName = BuildTargetFactory.newInstance("//javasrc:java-library");
    DefaultJavaLibraryRule javaLibraryRule = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(javaLibraryTargetName)
        .addSrc(Paths.get("javasrc/JavaLibrary.java")));

    String projectConfigTargetName = "//javasrc:project-config";
    ProjectConfigRule ruleConfig = ruleResolver.buildAndAddToIndex(
        ProjectConfigRule.newProjectConfigRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance(projectConfigTargetName))
        .setSrcTarget(Optional.of(javaLibraryTargetName)));

    BuckConfig buckConfig = createBuckConfig(
        Joiner.on("\n").join(
            "[project]",
            "initial_targets = " + javaLibraryTargetName));

    ProjectCommandForTest command = new ProjectCommandForTest();
    command.createPartialGraphCallReturnValues.push(
        createGraphFromBuildRules(ImmutableList.<BuildRule>of(ruleConfig)));

    command.runCommandWithOptions(createOptions(buckConfig));

    assertTrue(command.createPartialGraphCallReturnValues.isEmpty());

    // The PartialGraph comprises build config rules.
    RawRulePredicate projectConfigPredicate = command.createPartialGraphCallPredicates.get(0);
    checkPredicate(projectConfigPredicate, EMPTY_PARSE_DATA, javaLibraryRule, false);
    checkPredicate(projectConfigPredicate, EMPTY_PARSE_DATA, ruleConfig, true);

    BuildCommandOptions buildOptions = command.buildCommandOptions;
    MoreAsserts.assertContainsOne(
        buildOptions.getArguments(), javaLibraryTargetName.getFullyQualifiedName());
  }


  BuckConfig createBuckConfig(String contents)
      throws IOException, NoSuchBuildTargetException {
    ProjectFilesystem dummyProjectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.replay(dummyProjectFilesystem);
    return BuckConfig.createFromReader(
        new StringReader(contents),
        dummyProjectFilesystem,
        new BuildTargetParser(dummyProjectFilesystem),
        Platform.detect());
  }

  private static void checkPredicate(
      RawRulePredicate predicate,
      ImmutableMap<String, Object> rawParseData,
      BuildRule rule,
      boolean expectMatch) {
    assertEquals(
        expectMatch,
        predicate.isMatch(rawParseData, rule.getType(), rule.getBuildTarget()));
  }

  private ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  private PartialGraph createGraphFromBuildRules(List<BuildRule> rules) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();
    for (BuildRule rule : rules) {
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }

    List<BuildTarget> buildTargets = Lists.transform(rules, new Function<BuildRule, BuildTarget>() {
      @Override
      public BuildTarget apply(BuildRule rule) {
        return rule.getBuildTarget();
      }
    });

    DependencyGraph dependencyGraph = new DependencyGraph(graph);
    return PartialGraphFactory.newInstance(dependencyGraph, buildTargets);
  }

  /**
   * A subclass of ProjectCommand that captures some of the calls we want to verify
   * without actually running them.
   *
   * This code ends up being simpler than the equivalent EasyMock version.  And I hit what
   * appears to be a bug in EasyMock.
   */
  private static class ProjectCommandForTest extends ProjectCommand {
    private List<RawRulePredicate> createPartialGraphCallPredicates = Lists.newArrayList();
    private LinkedList<PartialGraph> createPartialGraphCallReturnValues = Lists.newLinkedList();
    private BuildCommandOptions buildCommandOptions;

    ProjectCommandForTest() {
      super(new CommandRunnerParams(
          new TestConsole(),
          new ProjectFilesystem(new File(".")),
          new FakeAndroidDirectoryResolver(),
          KnownBuildRuleTypes.getDefault(),
          new InstanceArtifactCacheFactory(artifactCache),
          BuckEventBusFactory.newInstance(),
          BuckTestConstant.PYTHON_INTERPRETER,
          Platform.detect()));
    }

    @Override
    PartialGraph createPartialGraph(RawRulePredicate rulePredicate, ProjectCommandOptions options)
        throws BuildFileParseException, NoSuchBuildTargetException {
      assertNotNull(options);
      createPartialGraphCallPredicates.add(rulePredicate);
      return createPartialGraphCallReturnValues.removeFirst();
    }

    @Override
    int createIntellijProject(Project project,
        File jsonTemplate,
        ProcessExecutor processExecutor,
        PrintStream stdOut,
        PrintStream stdErr)
        throws IOException {
      assertNotNull(project);
      assertNotNull(jsonTemplate);
      assertNotNull(processExecutor);
      assertNotNull(stdOut);
      assertNotNull(stdErr);
      return 0;
    }

    @Override
    int runBuildCommand(BuildCommand buildCommand, BuildCommandOptions options)
        throws IOException {
      assertNotNull(buildCommand);
      assertNotNull(options);
      assertNull(buildCommandOptions);

      buildCommandOptions = options;
      return 0;
    }
  }
}
