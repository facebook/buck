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

package com.facebook.buck.android;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class Aapt2PackageResourcesIntegrationTest {

  private static final String BUILD = "build";
  private static final String AAPT2_BUILD_TARGET = "//apps/sample:app_bundle";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void aapt2CompileInvokesAfterResourceChange() throws IOException {
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeBundleBuildIsSupported();
    workspace.runBuckdCommand(BUILD, AAPT2_BUILD_TARGET);
    verifyAapt2RuleInvocation(workspace.getBuildLog());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents(
        "res/com/sample/base/res/values/strings.xml",
        "Hello world!",
        "Hello from " + getClass().getName() + " !!!");

    workspace.runBuckdCommand(BUILD, AAPT2_BUILD_TARGET);
    verifyAapt2RuleInvocation(workspace.getBuildLog());
  }

  private void verifyAapt2RuleInvocation(BuckBuildLog buildLog) {
    BuckBuildLog.BuildLogEntry logEntry =
        buildLog.getLogEntry("//res/com/sample/base:base#aapt2_compile");
    assertThat(logEntry.getStatus(), is(equalTo(BuildRuleStatus.SUCCESS)));
    assertThat(logEntry.getSuccessType().get(), is(equalTo(BuildRuleSuccessType.BUILT_LOCALLY)));
  }
}
