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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
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
    ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());
    Config rawConfig =
        Configs.createDefaultConfig(projectFilesystem.getRootPath(), CellConfig.of());
    BuckConfig config = new BuckConfig(
        rawConfig,
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        environment);

    ParserConfig parserConfig = new ParserConfig(config);
    PythonBuckConfig pythonBuckConfig = new PythonBuckConfig(
        config,
        new ExecutableFinder());
    ImmutableSet<Description<?>> allDescriptions =
        DefaultKnownBuildRuleTypes
        .getDefaultKnownBuildRuleTypes(projectFilesystem, environment)
        .getAllDescriptions();
    SrcRootsFinder srcRootsFinder = new SrcRootsFinder(projectFilesystem);
    ProjectBuildFileParserFactory projectBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(projectFilesystem.getRootPath())
                .setPythonInterpreter(pythonBuckConfig.getPythonInterpreter())
                .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
                .setIgnorePaths(projectFilesystem.getIgnorePaths())
                .setBuildFileName(parserConfig.getBuildFileName())
                .setDefaultIncludes(parserConfig.getDefaultIncludes())
                .setDescriptions(allDescriptions)
                .setEnableBuildFileSandboxing(parserConfig.getEnableBuildFileSandboxing())
                .build());
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    JavaSymbolFinder finder = new JavaSymbolFinder(
        projectFilesystem,
        srcRootsFinder,
        DEFAULT_JAVAC_OPTIONS,
        new ConstructorArgMarshaller(new DefaultTypeCoercerFactory(
            ObjectMappers.newDefaultInstance())),
        projectBuildFileParserFactory,
        config,
        buckEventBus,
        new TestConsole(),
        environment);

    SetMultimap<String, BuildTarget> foundTargets =
        finder.findTargetsForSymbols(ImmutableSet.of("com.example.a.A"));

    assertEquals(
        "JavaSymbolFinder failed to find the right target.",
        ImmutableSetMultimap.of(
            "com.example.a.A",
            BuildTargetFactory.newInstance(projectFilesystem, "//java/com/example/a:a")),
        foundTargets);
  }
}
