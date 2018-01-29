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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CxxIncludeDirsIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.MACOS, Platform.LINUX));
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "include_dirs", tmp);
    workspace.setUp();
  }

  @Test
  public void cxxLibraryWithIncludeDirs() throws IOException {
    workspace.runBuckBuild("//cxx_library:lib2").assertSuccess();
    workspace.runBuckBuild("//cxx_library:lib3").assertSuccess();
  }

  @Test
  public void cxxLibraryWithoutIncludeDirs() throws IOException {
    workspace.replaceFileContents("cxx_library/BUCK", "include_dirs", "#");
    ProcessResult lib2Result = workspace.runBuckBuild("//cxx_library:lib2");
    lib2Result.assertFailure();
    assertThat(lib2Result.getStderr(), containsString("lib2.h"));

    ProcessResult lib3Result = workspace.runBuckBuild("//cxx_library:lib3");
    lib3Result.assertFailure();
    assertThat(lib3Result.getStderr(), containsString("lib3.h"));
  }

  @Test
  public void cxxBinaryWithIncludeDirs() throws IOException {
    workspace.runBuckBuild("//cxx_binary:bin2").assertSuccess();
  }

  @Test
  public void cxxBinaryWithoutIncludeDirs() throws IOException {
    workspace.replaceFileContents("cxx_binary/BUCK", "include_dirs", "#");
    ProcessResult bin2Result = workspace.runBuckBuild("//cxx_binary:bin2");
    bin2Result.assertFailure();
    assertThat(bin2Result.getStderr(), containsString("bin2.h"));
  }

  @Test
  public void cxxTestWithIncludeDirs() throws IOException {
    workspace.runBuckBuild("//cxx_test:test2").assertSuccess();
  }

  @Test
  public void cxxTestWithoutIncludeDirs() throws IOException {
    workspace.replaceFileContents("cxx_test/BUCK", "include_dirs", "#");
    ProcessResult test2Result = workspace.runBuckBuild("//cxx_test:test2");
    test2Result.assertFailure();
    assertThat(test2Result.getStderr(), containsString("test2.h"));
  }

  @Test
  public void cxxPythonExtensionWithIncludeDirs() throws IOException {
    workspace.runBuckBuild("//python_binary:binary_with_extension").assertSuccess();
  }

  @Test
  public void cxxPythonExtensionWithoutIncludeDirs() throws IOException {
    workspace.replaceFileContents("python_binary/BUCK", "include_dirs", "#");
    ProcessResult buildResult = workspace.runBuckBuild("//python_binary:binary_with_extension");
    buildResult.assertFailure();
    assertThat(buildResult.getStderr(), containsString("extension.h"));
  }
}
