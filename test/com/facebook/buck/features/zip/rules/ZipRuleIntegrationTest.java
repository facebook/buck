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

package com.facebook.buck.features.zip.rules;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ZipRuleIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void shouldZipSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:ziptastic");

    // Make sure we have the right files and attributes.
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.notNullValue());
      assertFalse(cake.isUnixSymlink());
      assertFalse(cake.isDirectory());

      ZipArchiveEntry beans = zipFile.getEntry("beans/");
      assertThat(beans, Matchers.notNullValue());
      assertFalse(beans.isUnixSymlink());
      assertTrue(beans.isDirectory());

      ZipArchiveEntry cheesy = zipFile.getEntry("beans/cheesy.txt");
      assertThat(cheesy, Matchers.notNullValue());
      assertFalse(cheesy.isUnixSymlink());
      assertFalse(cheesy.isDirectory());
    }
  }

  @Test
  public void shouldUnpackContentsOfASrcJar() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:unrolled");

    ZipInspector inspector = new ZipInspector(zip);
    inspector.assertFileExists("menu.txt");
  }

  @Test
  public void shouldSupportInputBasedRuleKey() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();
    // Warm the cache
    workspace.runBuckBuild("//example:inputbased");
    // Edit src in a non-output affecting fashion
    workspace.replaceFileContents("example/A.java", "ReplaceMe", "");
    // Re-build and expect input-based hit
    workspace.runBuckBuild("//example:inputbased");
    workspace.getBuildLog().assertTargetBuiltLocally("//example:lib");
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey("//example:inputbased");
  }

  @Test
  public void shouldFlattenZipsIfRequested() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-flatten", tmp);
    workspace.setUp();
    // Warm the cache
    Path zip = workspace.buildAndReturnOutput("//example:flatten");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.notNullValue());

      ZipArchiveEntry beans = zipFile.getEntry("beans.txt");
      assertThat(beans, Matchers.notNullValue());
    }
  }

  @Test
  public void shouldNotMergeSourceJarsIfRequested() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-merge", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:no-merge");

    // Gather expected file names
    Path sourceJar = workspace.buildAndReturnOutput("//example:cake#src");
    Path actualJar = workspace.buildAndReturnOutput("//example:cake");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry item = zipFile.getEntry(sourceJar.getFileName().toString());
      assertThat(item, Matchers.notNullValue());

      item = zipFile.getEntry(actualJar.getFileName().toString());
      assertThat(item, Matchers.notNullValue());

      item = zipFile.getEntry("cake.txt");
      assertThat(item, Matchers.notNullValue());
    }
  }

  @Test
  public void shouldExcludeEverything() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:excludeall");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.nullValue());
      ZipArchiveEntry taco = zipFile.getEntry("menu.txt");
      assertThat(taco, Matchers.nullValue());
    }
  }

  @Test
  public void shouldExcludeNothing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:excludenothing");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.notNullValue());
      ZipArchiveEntry taco = zipFile.getEntry("menu.txt");
      assertThat(taco, Matchers.notNullValue());
    }
  }

  @Test
  public void shouldExcludeNothingInSubDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:excludesnothinginsubfolder");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.notNullValue());
      ZipArchiveEntry cheesy = zipFile.getEntry("beans/cheesy.txt");
      assertThat(cheesy, Matchers.notNullValue());
    }
  }

  @Test
  public void shouldExcludeOneFileInSubDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:excludesexactmatchinsubfolder");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.notNullValue());
      ZipArchiveEntry cheesy = zipFile.getEntry("beans/cheesy.txt");
      assertThat(cheesy, Matchers.nullValue());
    }
  }

  @Test
  public void shouldExcludeOnlyCake() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:excludecake");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.nullValue());
      ZipArchiveEntry taco = zipFile.getEntry("menu.txt");
      assertThat(taco, Matchers.notNullValue());
    }
  }

  @Test
  public void shouldUnpackContentsOfZipSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:zipsources");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipArchiveEntry menu = zipFile.getEntry("menu.txt");
      assertThat(menu, Matchers.notNullValue());
      assertFalse(menu.isUnixSymlink());
      assertFalse(menu.isDirectory());
      ZipArchiveEntry cake = zipFile.getEntry("cake.txt");
      assertThat(cake, Matchers.notNullValue());
      assertFalse(cake.isUnixSymlink());
      assertFalse(cake.isDirectory());
    }
  }

  @Test
  public void shouldThrowExceptionWhenZipSourcesAndMergeSourcesDefined() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild("//example:zipbreak");
    processResult.assertExitCode(ExitCode.FATAL_GENERIC);
    assertThat(
        processResult.getStderr(),
        containsString("Illegal to define merge_source_zips when zip_srcs is present"));
  }

  @Test
  public void shouldOnlyUnpackContentsOfZipSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:zipsources");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipInspector inspector = new ZipInspector(zip);
      inspector.assertFileDoesNotExist("taco.txt");
    }
  }

  @Test
  public void shouldExcludeFromRegularZip() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:exclude_from_zip");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipInspector inspector = new ZipInspector(zip);
      inspector.assertFileDoesNotExist("cake.txt");
    }
  }

  @Test
  public void shouldCopyFromGenruleOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:copy_zip");

    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipInspector inspector = new ZipInspector(zip);
      inspector.assertFileExists("copy_out/cake.txt");
    }
  }

  @Test
  public void shouldOverwriteDuplicates() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:overwrite_duplicates");
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipInspector inspector = new ZipInspector(zip);
      inspector.assertFileContents(Paths.get("cake.txt"), "Cake :)");
    }
  }

  @Test
  public void testOrderInZipSrcsAffectsResults() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-rule", tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:overwrite_duplicates_in_different_order");
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipInspector inspector = new ZipInspector(zip);
      inspector.assertFileContents(Paths.get("cake.txt"), "Guten Tag");
    }
  }

  @Test
  public void testShouldIncludeOutputsContainedInBuckOutOfOtherCells() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "zip-crosscell", tmp);
    workspace.setUp();

    Path childRepoRoot = workspace.getPath("parent/child");
    ProcessResult buildResult =
        workspace.runBuckCommand(childRepoRoot, "build", "--show-output", "//:exported-zip");
    buildResult.assertSuccess();

    String outputRelpathString =
        workspace.parseShowOutputStdoutAsStrings(buildResult.getStdout()).get("//:exported-zip");
    Path zip = workspace.getPath("parent/child/" + outputRelpathString);
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipInspector inspector = new ZipInspector(zip);
      assertThat(inspector.getZipFileEntries().size(), greaterThan(0));
    }
  }
}
