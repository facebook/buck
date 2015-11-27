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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.InferHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;


public class InferMergeReportsStepTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private InferMergeReportsStep.InferReportMerger reportMerger;
  private Path mergedReport;
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.<String>absent());

    Path report1 = Paths.get("report1");
    Path report2 = Paths.get("report2");
    Path report3 = Paths.get("report3");

    String content1 = "[{\"field_one\":1, \"field_two\":\"value\"}]";
    String content2 = "[]";
    String content3 = "[{\"field_one\":1, \"field_two\":\"value\"}, " +
        "{\"field_one\":7, \"field_two\":\"wow\"}]";

    workspace.writeContentsToPath(content1, report1.toString());
    workspace.writeContentsToPath(content2, report2.toString());
    workspace.writeContentsToPath(content3, report3.toString());

    ProjectFilesystem fs = new ProjectFilesystem(tmp.getRootPath());

    ImmutableSortedSet<Path> reportsToMerge = ImmutableSortedSet.of(
        workspace.resolve(report1),
        workspace.resolve(report2),
        workspace.resolve(report3));
    mergedReport = workspace.resolve(Paths.get("finalReport"));
    reportMerger =
        new InferMergeReportsStep(fs, reportsToMerge, mergedReport)
            .new InferReportMerger(reportsToMerge, mergedReport);
  }

  @Test
  public void testIsReportEmpty() {
    assertTrue("Should be empty", reportMerger.isReportEmpty("[]"));
    assertTrue("Should be empty", reportMerger.isReportEmpty("[ ]"));
    assertTrue("Should be empty", reportMerger.isReportEmpty("  [ ]   "));
    assertTrue("Should be empty", reportMerger.isReportEmpty("  \n[ \n\t \n]  \n"));
    assertFalse(
        "Should not be empty",
        reportMerger.isReportEmpty("[{\"a\":2,\"b\":\"aaa\nbbb\"}]"));
    assertFalse(
        "Should not be empty",
        reportMerger.isReportEmpty("[\n\n { \"a\":2,\"b\":\"aaa\nbbb\"  }\t]"));
  }

  @Test
  public void testStripArrayTokens() {
    String result = reportMerger.stripArrayTokens("[]");
    assertThat("Should strip the surrounding square brackets", result, Matchers.equalTo(""));

    result = reportMerger.stripArrayTokens("[ ]");
    assertThat("Should strip the surrounding square brackets", result, Matchers.equalTo(" "));

    result = reportMerger.stripArrayTokens("  [ ]   ");
    assertThat("Should strip the surrounding square brackets", result, Matchers.equalTo(" "));

    result = reportMerger.stripArrayTokens("  \n[ \n\n \n]  \n");
    assertThat(
        "Should strip the surrounding square brackets",
        result,
        Matchers.equalTo(" \n\n \n"));

    result = reportMerger.stripArrayTokens("  \n[{\"a\":2,\"b\":\"aaa\nbbb\"}  ]  \n");
    assertThat(
        "Should strip the surrounding square brackets",
        result,
        Matchers.equalTo("{\"a\":2,\"b\":\"aaa\nbbb\"}  "));

    result = reportMerger.stripArrayTokens("  \n[[{\"a\":2,\"b\":\"aaa\nbbb\"}]]  \n");
    assertThat(
        "Should strip the surrounding square brackets",
        result,
        Matchers.equalTo("[{\"a\":2,\"b\":\"aaa\nbbb\"}]"));
  }

  @Test
  public void testFinalizeWithEmptyReports() throws IOException {
    reportMerger.initializeReport();
    reportMerger.appendReport("[]");
    reportMerger.appendReport("\n[\n\n ]\n");
    reportMerger.appendReport(" \t [     ]  ");
    reportMerger.finalizeReport();
    List<Object> bugs = InferHelper.loadInferReport(workspace, mergedReport.toString());
    assertThat(
        "0 bugs expected in " + mergedReport + " not found",
        bugs.size(),
        Matchers.equalTo(0));
    String result = workspace.getFileContents(mergedReport.toString());
    assertThat("Should be an empty array", result, Matchers.equalTo("[]"));
  }

  @Test
  public void testFinalizeWithOneReport() throws IOException {
    reportMerger.initializeReport();
    reportMerger.appendReport(
        "\n [{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ]\n ");
    reportMerger.finalizeReport();
    List<Object> bugs = InferHelper.loadInferReport(workspace, mergedReport.toString());
    assertThat(
        "1 bugs expected in " + mergedReport + " not found",
        bugs.size(),
        Matchers.equalTo(1));
    String result = workspace.getFileContents(mergedReport.toString());
    assertThat(
        "Should be an array with one object",
        result,
        Matchers.equalTo(
            "[{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ]"));
  }

  @Test
  public void testFinalizeWithEmptyAndRegularReports() throws IOException {
    reportMerger.initializeReport();
    reportMerger.appendReport("\n[ ] \n\n");
    reportMerger.appendReport(
        "\n [{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ]\n ");
    reportMerger.appendReport("[]");
    reportMerger.appendReport(
        "[{ \"file\":\"aFile.c\", \"type\":\"RESOURCE_LEAK\", \"line\":456}] ");
    reportMerger.appendReport(
        "\n [{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":789 } ]\n ");
    reportMerger.finalizeReport();
    List<Object> bugs = InferHelper.loadInferReport(workspace, mergedReport.toString());
    assertThat(
        "3 bugs expected in " + mergedReport + " not found",
        bugs.size(),
        Matchers.equalTo(3));
    String result = workspace.getFileContents(mergedReport.toString());
    assertThat(
        "Should be an array with three objects",
        result,
        Matchers.equalTo(
            "[" +
                "{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ," +
                "{ \"file\":\"aFile.c\", \"type\":\"RESOURCE_LEAK\", \"line\":456}," +
                "{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":789 } " +
                "]"));
  }

  @Test
  public void testMerge() throws IOException {
    reportMerger.merge();
    List<Object> bugs = InferHelper.loadInferReport(workspace, mergedReport.toString());
    assertThat(
        "3 bugs expected in " + mergedReport + " not found",
        bugs.size(),
        Matchers.equalTo(3));
  }


}
