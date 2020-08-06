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

package com.facebook.buck.core.model.targetgraph.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.visibility.VisibilityError;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration tests for {@link com.facebook.buck.core.model.targetgraph.TargetGraph} visibility
 * checking.
 */
public class TargetGraphVisibilityIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "target_visibility", tmp);
    workspace.setUp();
  }

  @Test
  public void validGraphSucceeds() {
    assertEquals(
        "//foo:Lib1", workspace.runBuckCommand("targets", "//foo:Lib1").getStdout().trim());
  }

  @Test
  public void singleVisibilityError() {
    ProcessResult result = workspace.runBuckCommand("targets", "//foo:Lib2");
    result.assertFailure();

    verifySingleError(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.VISIBILITY, "//foo:Lib2", "//bar:Lib2"));
  }

  @Test
  public void multipleVisibilityErrors() {
    ProcessResult result = workspace.runBuckCommand("targets", "//foo:Lib3");
    result.assertFailure();

    verifyNumberOfErrors(result, 2);

    verifyErrorOutputContains(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.VISIBILITY, "//foo:Lib3", "//bar:Lib2"));
    verifyErrorOutputContains(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.VISIBILITY, "//foo:Lib3", "//bar:Lib3"));
  }

  @Test
  public void singleWithinViewError() {
    ProcessResult result = workspace.runBuckCommand("targets", "//bar:Lib5");
    result.assertFailure();

    verifySingleError(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.WITHIN_VIEW, "//bar:Lib5", "//foo:Lib5"));
  }

  @Test
  public void mixedErrors() {
    ProcessResult result = workspace.runBuckCommand("targets", "//foo:Lib4");
    result.assertFailure();

    verifyNumberOfErrors(result, 4);

    verifyErrorOutputContains(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.VISIBILITY, "//foo:Lib4", "//bar:Lib3"));

    verifyErrorOutputContains(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.WITHIN_VIEW, "//foo:Lib4", "//bar:Lib1"));
    verifyErrorOutputContains(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.WITHIN_VIEW, "//foo:Lib4", "//bar:Lib2"));
    verifyErrorOutputContains(
        result,
        VisibilityError.errorString(
            VisibilityError.ErrorType.WITHIN_VIEW, "//foo:Lib4", "//bar:Lib4"));
  }

  private void verifyErrorOutputContains(ProcessResult result, String error) {
    String errorWithNewline = error + "\n";
    assertTrue(result.getStderr().contains(errorWithNewline));
  }

  private void verifyNumberOfErrors(ProcessResult result, int count) {
    long counted =
        Stream.of(result.getStderr().split("\n")).filter(s -> s.contains("which is not")).count();
    assertEquals(count, counted);
  }

  private void verifySingleError(ProcessResult result, String error) {
    verifyNumberOfErrors(result, 1);
    verifyErrorOutputContains(result, error);
  }
}
