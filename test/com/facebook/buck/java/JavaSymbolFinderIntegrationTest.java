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

package com.facebook.buck.java;

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.rules.Description;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class JavaSymbolFinderIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void shouldFindTargetDefiningSymbol() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "symbol_finder", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());

    BuckConfig config = BuckConfig.createFromFiles(
        projectFilesystem,
        ImmutableList.of(projectFilesystem.getFileForRelativePath(".buckconfig")),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
    ImmutableSet<Description<?>> allDescriptions =
        DefaultKnownBuildRuleTypes
        .getDefaultKnownBuildRuleTypes(projectFilesystem)
        .getAllDescriptions();
    SrcRootsFinder srcRootsFinder = new SrcRootsFinder(projectFilesystem);
    ProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            projectFilesystem,
            new ParserConfig(config),
            allDescriptions);
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    JavaSymbolFinder finder = new JavaSymbolFinder(
        projectFilesystem,
        srcRootsFinder,
        DEFAULT_JAVAC_OPTIONS,
        projectBuildFileParserFactory,
        config,
        buckEventBus,
        new TestConsole(),
        ImmutableMap.copyOf(System.getenv()));

    SetMultimap<String, BuildTarget> foundTargets =
        finder.findTargetsForSymbols(ImmutableSet.of("com.example.a.A"));

    assertEquals(
        "JavaSymbolFinder failed to find the right target.",
        ImmutableSetMultimap.of(
            "com.example.a.A",
            BuildTarget.builder("//java/com/example/a", "a").build()),
        foundTargets);
  }
}
