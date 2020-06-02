/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.supermodule;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class SupermoduleTargetGraphIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Test
  public void jsonOutputForDepGraphIsCorrect() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "graph-validation", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//Apps/TestApp:TestAppSupermoduleGraph").assertSuccess();
  }
}
