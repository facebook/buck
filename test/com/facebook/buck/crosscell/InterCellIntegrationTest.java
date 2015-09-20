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

package com.facebook.buck.crosscell;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.Pair;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.ini4j.Ini;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class InterCellIntegrationTest {

  @Rule
  public TemporaryPaths primaryTmp = new TemporaryPaths();
  @Rule
  public TemporaryPaths secondaryTmp = new TemporaryPaths();

  @Test
  public void ensureThatNormalBuildsWorkAsExpected() throws IOException {
    ProjectWorkspace secondary = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "inter-cell/export-file/secondary",
        primaryTmp);
    secondary.setUp();

    ProjectWorkspace.ProcessResult result = secondary.runBuckBuild("//:hello");

    result.assertSuccess();
  }

  @Test
  public void shouldBeAbleToUseAnExportFileXRepoTarget() throws IOException {
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(
        "inter-cell/export-file/primary",
        "inter-cell/export-file/secondary");
    ProjectWorkspace primary = cells.getFirst();
    ProjectWorkspace secondary = cells.getSecond();

    String expected = secondary.getFileContents("hello.txt");
    Path path = primary.buildAndReturnOutput("//:exported-file");

    String actual = new String(Files.readAllBytes(path), UTF_8);

    assertEquals(expected, actual);
  }

  @Test
  @Ignore
  public void shouldBeAbleToUseAJavaLibraryTargetXCell() {

  }

  @Test
  @Ignore
  public void javaVersionsSetInACellShouldBeRespectedForXCellBuilds() {

  }

  @Test
  @Ignore
  public void buildFileNamesCanBeDifferentCrossCell() {

  }

  @Test
  @Ignore
  public void allOutputsShouldBePlacedInTheSameRootOutputFolder() {

  }

  private Pair<ProjectWorkspace, ProjectWorkspace> prepare(
      String primaryPath,
      String secondaryPath) throws IOException {
    ProjectWorkspace secondary = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        secondaryPath,
        secondaryTmp);
    secondary.setUp();

    ProjectWorkspace primary = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        primaryPath,
        primaryTmp);
    primary.setUp();

    String config = primary.getFileContents(".buckconfig");
    Ini ini = new Ini(new StringReader(config));
    ini.put("repositories", "secondary", secondary.getPath(".").normalize().toAbsolutePath());
    StringWriter writer = new StringWriter();
    ini.store(writer);
    Files.write(primary.getPath(".buckconfig"), writer.toString().getBytes(UTF_8));

    return new Pair<>(primary, secondary);
  }
}
