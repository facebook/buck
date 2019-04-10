/*
 * Copyright 2018-present Facebook, Inc.
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

import static com.google.common.base.Charsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.pf4j.PluginManager;

@RunWith(Parameterized.class)
public class PerBuildStateTest {
  @Rule public TemporaryPaths tempDir = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  private final int threads;
  private final boolean parallelParsing;
  private Path cellRoot;
  private Cell cell;
  private PerBuildState perBuildState;

  public PerBuildStateTest(int threads, boolean parallelParsing) {
    this.threads = threads;
    this.parallelParsing = parallelParsing;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> generateData() {
    return Arrays.asList(
        new Object[][] {
          {
            1, false,
          },
          {
            1, true,
          },
          {
            2, true,
          },
        });
  }

  @Before
  public void setUp() {
    // Create a temp directory with some build files.
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot());
    cellRoot = filesystem.getRootPath();

    ImmutableMap.Builder<String, String> projectSectionBuilder = ImmutableMap.builder();
    projectSectionBuilder.put("allow_symlinks", "warn");
    if (parallelParsing) {
      projectSectionBuilder.put("parallel_parsing", "true");
      projectSectionBuilder.put("parsing_threads", Integer.toString(threads));
    }

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.<String, ImmutableMap<String, String>>builder()
                    .put("project", projectSectionBuilder.build())
                    .build())
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);
    Parser parser =
        TestParserFactory.create(cell, knownRuleTypesProvider, BuckEventBusForTests.newInstance());

    perBuildState = TestPerBuildStateFactory.create(parser, cell);
  }

  @Test
  public void loadedBuildFileWithoutLoadedTargetNodesLoadsAdditionalTargetNodes()
      throws IOException, BuildFileParseException {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK").toRealPath();
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib1')\njava_library(name = 'lib2')\n".getBytes(UTF_8));
    BuildTarget fooLib1Target = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib1");
    BuildTarget fooLib2Target = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib2");

    // First, only load one target from the build file so the file is parsed, but only one of the
    // TargetNodes will be cached.
    TargetNode<?> targetNode = perBuildState.getTargetNode(fooLib1Target);
    assertThat(targetNode.getBuildTarget(), equalTo(fooLib1Target));

    // Now, try to load the entire build file and get all TargetNodes.
    ImmutableList<TargetNode<?>> targetNodes =
        perBuildState.getAllTargetNodes(cell, testFooBuckFile, EmptyTargetConfiguration.INSTANCE);
    assertThat(targetNodes.size(), equalTo(2));
    assertThat(
        targetNodes.stream()
            .map(TargetNode::getBuildTarget)
            .collect(ImmutableList.toImmutableList()),
        hasItems(fooLib1Target, fooLib2Target));
  }
}
