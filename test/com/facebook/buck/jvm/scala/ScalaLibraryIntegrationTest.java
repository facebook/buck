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

package com.facebook.buck.jvm.scala;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeNoException;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ScalaLibraryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "scala_binary", tmp);
    workspace.setUp();
  }

  @Test(timeout = (2 * 60 * 1000))
  public void shouldCompileScalaClass() throws Exception {
    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "scala.compiler=buck//third-party/scala:scala-compiler",
                "//:bin",
                "--",
                "world!")
            .assertSuccess()
            .getStdout(),
        Matchers.containsString("Hello WORLD!"));
  }

  @Test(timeout = (2 * 60 * 1000))
  public void shouldWorkWithLocalCompiler() throws Exception {
    try {
      new ScalaBuckConfig(FakeBuckConfig.builder().build()).getScalac(new TestActionGraphBuilder());
    } catch (HumanReadableException e) {
      assumeNoException("Could not find local scalac", e);
    }

    assertThat(
        workspace.runBuckCommand("run", "//:bin", "--", "world!").assertSuccess().getStdout(),
        Matchers.containsString("Hello WORLD!"));
  }

  @Test(timeout = (2 * 60 * 1000))
  public void scalacShouldAffectRuleKey() throws Exception {
    String firstRuleKey =
        workspace
            .runBuckCommand(
                "targets",
                "--config",
                "scala.compiler=//:fake-scala-compiler",
                "--show-rulekey",
                "//:bin")
            .assertSuccess()
            .getStdout()
            .trim();

    workspace.writeContentsToPath("changes", "scalac.sh");

    String secondRuleKey =
        workspace
            .runBuckCommand(
                "targets",
                "--config",
                "scala.compiler=//:fake-scala-compiler",
                "--show-rulekey",
                "//:bin")
            .assertSuccess()
            .getStdout()
            .trim();

    assertThat(secondRuleKey, not(equalTo(firstRuleKey)));
  }

  @Test(timeout = (2 * 60 * 1000))
  public void shouldCompileMixedJavaAndScalaSources() throws Exception {
    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "scala.compiler=buck//third-party/scala:scala-compiler",
                "//:bin_mixed",
                "--",
                "world!")
            .assertSuccess()
            .getStdout(),
        Matchers.containsString("Hello WORLD!"));
  }
}
