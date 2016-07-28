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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AutodepsCommandIntegrationTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void necessarySymbolHasASingleProviderInAPublicRule() throws IOException {
    verifyBasicBuckAutodepsCase();
  }

  @Test
  public void malformedBuckAutodepsFileDoesNotBreakBuckAutodeps() throws IOException {
    ProjectWorkspace workspace = verifyBasicBuckAutodepsCase();

    workspace.writeContentsToPath(
        "This is not valid contents for a BUCK.autodeps file",
        "BUCK.autodeps");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 file written.\n"));
    workspace.verify();
  }

  private ProjectWorkspace verifyBasicBuckAutodepsCase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_single_public_provider", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 file written.\n"));
    workspace.verify();
    return workspace;
  }

  @Test
  public void necessarySymbolHasMultipleProvidersButOnlyOneIsVisible() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_multiple_public_providers_only_one_public", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 file written.\n"));
    workspace.verify();
  }

  @Test
  public void warnWhenThereAreMultipleVisibleProviders() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_multiple_public_providers", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("0 files written.\n"));
    assertThat(
        result.getStderr(),
        containsString(
            "WARNING: Multiple providers for com.example.Bar: //alternate_bar:bar, //bar:bar. " +
            "Consider adding entry to .buckconfig to eliminate ambiguity:\n" +
            "[autodeps]\n" +
            "java-package-mappings = com.example.Bar => //alternate_bar:bar"
        )
    );
    workspace.verify();
  }

  @Test
  public void necessarySymbolHasMappingInBuckConfig() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_single_provider_known_only_via_buckconfig", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 file written.\n"));
    workspace.verify();
  }

  @Test
  public void necessarySymbolProvidedByProvidedDepsShouldNotAddProviderToGeneratedDeps()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_provider_in_provided_deps", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 file written.\n"));
    workspace.verify();
  }

  @Test
  public void necessarySymbolHasNoVisibleProviderButARuleThatExportsTheNecessarySymbolIsVisible()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_provider_available_only_via_exported_deps", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 file written.\n"));
    workspace.verify();
  }

  @Test
  public void warnWhenThereAreZeroVisibleProvidersButMultipleRulesThatExportTheProvider()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "autodeps_java_provider_available_only_via_multiple_exported_deps", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("autodeps");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("0 files written.\n"));
    assertThat(
        result.getStderr(),
        containsString(
            "WARNING: No providers found for 'com.example.Bar' for build rule //foo:foo, " +
            "but there are multiple rules that export a rule to provide com.example.Bar: " +
            "//bar:public_bar1, //bar:public_bar2"
        )
    );
    workspace.verify();
  }
}
