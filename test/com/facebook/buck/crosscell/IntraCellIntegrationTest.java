/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.crosscell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.parser.DefaultParser;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.TargetSpecResolver;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.concurrent.Executors;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.pf4j.PluginManager;

public class IntraCellIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  @Ignore
  public void shouldTreatACellBoundaryAsAHardBuckPackageBoundary() {}

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void shouldTreatCellBoundariesAsVisibilityBoundariesToo()
      throws IOException, InterruptedException, BuildFileParseException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "intracell/visibility", tmp);
    workspace.setUp();

    // We don't need to do a build. It's enough to just parse these things.
    Cell cell = workspace.asCell();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);

    TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);
    Parser parser =
        new DefaultParser(
            new PerBuildStateFactory(
                coercerFactory,
                new ConstructorArgMarshaller(coercerFactory),
                knownRuleTypesProvider,
                new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder())),
            parserConfig,
            coercerFactory,
            new TargetSpecResolver());

    // This parses cleanly
    parser.buildTargetGraph(
        BuckEventBusForTests.newInstance(),
        cell,
        false,
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        ImmutableSet.of(
            BuildTargetFactory.newInstance(
                cell.getFilesystem().getRootPath(), "//just-a-directory:rule")));

    Cell childCell =
        cell.getCell(
            BuildTargetFactory.newInstance(
                workspace.getDestPath().resolve("child-repo"), "//:child-target"));

    try {
      // Whereas, because visibility is limited to the same cell, this won't.
      parser.buildTargetGraph(
          BuckEventBusForTests.newInstance(),
          childCell,
          false,
          MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
          ImmutableSet.of(
              BuildTargetFactory.newInstance(
                  childCell.getFilesystem().getRootPath(), "child//:child-target")));
      fail("Didn't expect parsing to work because of visibility");
    } catch (HumanReadableException e) {
      // This is expected
    }
  }

  @Test
  @Ignore
  public void allOutputsShouldBePlacedInTheSameRootOutputDirectory() {}

  @Test
  public void testEmbeddedBuckOut() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "intracell/visibility", tmp);
    workspace.setUp();
    Cell cell = workspace.asCell();
    assertEquals(cell.getFilesystem().getBuckPaths().getGenDir().toString(), "buck-out/gen");
    Cell childCell =
        cell.getCell(
            BuildTargetFactory.newInstance(
                workspace.getDestPath().resolve("child-repo"), "//:child-target"));
    assertEquals(
        childCell.getFilesystem().getBuckPaths().getGenDir().toString(),
        "../buck-out/cells/child/gen");
  }
}
