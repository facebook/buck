/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.haskell;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collection;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class HaskellLibraryIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.copyOf(
        new Object[][] {
          {Linker.LinkableDepType.STATIC},
          {Linker.LinkableDepType.STATIC_PIC},
          {Linker.LinkableDepType.SHARED},
        });
  }

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Parameterized.Parameter(value = 0)
  public Linker.LinkableDepType linkStyle;

  private String getLinkFlavor() {
    return linkStyle.toString().toLowerCase().replace('_', '-');
  }

  @Before
  public void setUp() throws IOException, InterruptedException {

    // We don't currently support windows.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    // Verify that the system contains a compiler.
    HaskellVersion version = HaskellTestUtils.assumeSystemCompiler();

    // Setup the workspace.
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "library_test", tmp);
    workspace.setUp();

    // Write out the `.buckconfig`.
    workspace.writeContentsToPath(HaskellTestUtils.formatHaskellConfig(version), ".buckconfig");
  }

  @Test
  public void simple() throws IOException {
    workspace.runBuckBuild("//:foo#default," + getLinkFlavor()).assertSuccess();
  }

  @Test
  public void dependency() throws IOException {
    workspace.runBuckBuild("//:dependent#default," + getLinkFlavor()).assertSuccess();
  }

  @Test
  public void foreign() throws IOException {
    workspace.runBuckBuild("//:foreign#default," + getLinkFlavor()).assertSuccess();
  }

  @Test
  public void firstOrderDeps() throws IOException {
    workspace.runBuckBuild("//:first_order_a_pass#default," + getLinkFlavor()).assertSuccess();
    ProcessResult result =
        workspace.runBuckBuild("//:first_order_a_fail#default," + getLinkFlavor()).assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.anyOf(
            Matchers.containsString("It is a member of the hidden package"), // < GHC 8.1
            Matchers.containsString("Could not find module"))); // > GHC 8.1
  }

  @Test
  public void order() throws IOException {
    workspace.writeContentsToPath("module OrderA where\nimport OrderB\n", "OrderA.hs");
    workspace.runBuckBuild("//:order#default," + getLinkFlavor()).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.writeContentsToPath("module OrderA where\n", "OrderA.hs");
    workspace.writeContentsToPath("module OrderB where\nimport OrderA\n", "OrderB.hs");
    workspace.runBuckBuild("//:order#default," + getLinkFlavor()).assertSuccess();
  }

  @Test
  public void cHeader() throws IOException {
    workspace.runBuckBuild("//:hs_header#default," + getLinkFlavor()).assertSuccess();
  }
}
