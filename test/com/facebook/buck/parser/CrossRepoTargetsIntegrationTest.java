/*
 * Copyright 2014-present Facebook, Inc.
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
package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RepositoryFactory;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;

public class CrossRepoTargetsIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder externalFolder = new DebuggableTemporaryFolder();
  @Rule
  public DebuggableTemporaryFolder mainFolder = new DebuggableTemporaryFolder();

  @Test
  public void testParsingCrossRepoTargets()
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    ProjectWorkspace external = TestDataHelper.createProjectWorkspaceForScenario(
        this, "crossrepo_external", externalFolder);
    external.setUp();

    ProjectWorkspace main = TestDataHelper.createProjectWorkspaceForScenario(
        this, "crossrepo_main", mainFolder);
    main.setUp();

    String repositoriesSection =
        "[repositories]\n" +
        "external = " + externalFolder.getRoot() + "\n";
    Files.append(repositoriesSection, main.getFile(".buckconfig"), Charset.defaultCharset());

    RepositoryFactory repositoryFactory = new FakeRepositoryFactory(mainFolder.getRoot().toPath());
    BuckConfig config = repositoryFactory.getRootRepository().getBuckConfig();

    Parser parser = Parser.createParser(
        repositoryFactory,
        new ParserConfig(config),
        new FakeRuleKeyBuilderFactory());

    BuildTarget mainTarget = BuildTarget.builder("//", "main").build();
    BuildTarget externalTarget =
        BuildTarget.builder("//", "external").setRepository("external").build();

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
        ImmutableList.of(mainTarget),
        new ParserConfig(config),
        eventBus,
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
    ActionGraph graph = new TargetGraphToActionGraph(
        eventBus, new BuildTargetNodeToBuildRuleTransformer())
        .apply(targetGraph);

    BuildRule mainRule = graph.findBuildRuleByTarget(mainTarget);
    assertEquals(mainTarget, mainRule.getBuildTarget());

    BuildRule externalRule = graph.findBuildRuleByTarget(externalTarget);
    assertEquals(externalTarget, externalRule.getBuildTarget());

    assertEquals(externalRule, mainRule.getDeps().first());
  }
}
