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

package com.facebook.buck.cli;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class FilteredTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void filteredTestsAreNeverBuilt() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "filtered_tests", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        Joiner.on('\n').join(
            ImmutableList.of(
                "[python]",
                "  path_to_python_test_main = " +
                    Paths.get("src/com/facebook/buck/python/__test_main__.py")
                        .toAbsolutePath()
                        .toString())),
        ".buckconfig");

    // This will attempt to build the broken test, //:broken, which will fail to build.
    workspace.runBuckCommand("test", "--all").assertFailure();

    // This will exclude building //:broken and will therefore pass the build and result
    // in a test failure while running //:bad_test.
    workspace.runBuckCommand("test", "--all", "--exclude", "flaky")
        .assertTestFailure("hello world");

    // As per above, but using "--build-filtered" will force //:broken to still be built
    // even though it was filtered out, resulting in a failed build.
    workspace.runBuckCommand("test", "--all", "--exclude", "flaky", "--build-filtered")
        .assertFailure();

    // Explicitly trying to test //:broken will fail at the build stage.
    workspace.runBuckCommand("test", "//:broken").assertFailure();

    // Since we're explicitly referencing //:broken on the command line, normal filtering will
    // *not* filter it out.  So we fail at the build stage.
    workspace.runBuckCommand("test", "//:broken", "--exclude", "flaky").assertFailure();

    // Using "--always_exclude" causes filters to override explicitly specified targets, so this
    // means we won't build //:broken and will therefore succeed (but run no tests).
    workspace.runBuckCommand("test", "//:broken", "--exclude", "flaky", "--always_exclude")
        .assertSuccess();

    // Passing "--build-filtered" means we'll still build //:broken, even though we're filtering
    // it, causing a build failure.
    workspace.runBuckCommand(
        "test", "//:broken", "--exclude", "flaky", "--always_exclude", "--build-filtered")
        .assertFailure();
  }

}
