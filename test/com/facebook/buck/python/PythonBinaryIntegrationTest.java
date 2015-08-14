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

package com.facebook.buck.python;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Collection;

@RunWith(Parameterized.class)
public class PythonBinaryIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[]{PythonBuckConfig.PackageStyle.INPLACE},
        new Object[]{PythonBuckConfig.PackageStyle.STANDALONE});
  }

  @Parameterized.Parameter
  public PythonBuckConfig.PackageStyle packageStyle;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "python_binary", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[python]\n" +
        "  package_style = " + packageStyle.toString().toLowerCase() + "\n",
        ".buckconfig");
    workspace.setUp();
  }

  @Test
  public void nonComponentDepsArePreserved() throws IOException {
    workspace.runBuckBuild("//:bar").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:extra");
  }

}
