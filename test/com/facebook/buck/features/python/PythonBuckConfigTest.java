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

package com.facebook.buck.features.python;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PythonBuckConfigTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public TemporaryPaths temporaryFolder2 = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {}

  @Test
  public void testPathToPexExecuterUsesConfigSetting() throws IOException {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Path projectDir = Files.createTempDirectory("project");
    Path pexExecuter = Paths.get("pex-executer");
    ProjectFilesystem projectFilesystem =
        new FakeProjectFilesystem(FakeClock.doNotCare(), projectDir, ImmutableSet.of(pexExecuter));
    Files.createFile(projectFilesystem.resolve(pexExecuter));
    assertTrue(
        "Should be able to set file executable",
        projectFilesystem.resolve(pexExecuter).toFile().setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python", ImmutableMap.of("path_to_pex_executer", pexExecuter.toString())))
                .setFilesystem(projectFilesystem)
                .build());
    assertThat(
        config.getPexExecutor(resolver).get().getCommandPrefix(pathResolver),
        Matchers.contains(projectDir.resolve(pexExecuter).toString()));
  }

  @Test
  public void testPythonPlatformsNotConstructedEagerly() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "python_platform", temporaryFolder);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", ":file").assertSuccess();
    assertThat(result.getStdout(), containsString("I'm a file. A lonely, lonely file."));
  }
}
