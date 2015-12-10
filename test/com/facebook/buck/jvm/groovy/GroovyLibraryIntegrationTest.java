/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.jvm.groovy;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;


public class GroovyLibraryIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    assumeTrue(System.getenv("GROOVY_HOME") != null);

    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "groovy_library_description", tmp);
    workspace.setUp();
  }

  @Test
  public void shouldCompileGroovyClass() throws Exception {
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertSuccess("Build should have succeeded.");

    workspace.verify();
  }

  @Test
  public void shouldCompileLibraryWithDependencyOnAnother() throws Exception {
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/child:child");
    buildResult.assertSuccess("Build should have succeeded.");

    workspace.verify();
  }

  @Test
  public void shouldFailToCompileInvalidGroovyClass() throws Exception {
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/bad:fail");
    buildResult.assertFailure();

    workspace.verify();
  }

  @Test
  public void shouldCrossCompileWithJava() throws Exception {
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/xcompile:xcompile");
    buildResult.assertSuccess();

    workspace.verify();
  }

  @Test
  public void javaOptionsArePassedThroughToTheJavacCompiler() throws Exception {
    // code requires java 7, source set to java 6
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/modern:xcompile");

    buildResult.assertFailure();

    workspace.verify();
  }

  @Test
  public void arbitraryJavaOptionsArePassedThrough() throws Exception {
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/javacextras:javacextras");

    buildResult.assertFailure();

    workspace.verify();
  }
}
