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

package com.facebook.buck.cxx;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@Ignore
public class CxxPrecompiledHeaderRuleDisabledTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private ProjectWorkspace workspace;

  @Before
  public void setUp() {
    CxxPrecompiledHeaderTestUtils.assumePrecompiledHeadersAreSupported();
  }

  @Test
  public void pchDisabledFailsOver() throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_precompiled_header_rule/pch_disabled/failover", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }
}
