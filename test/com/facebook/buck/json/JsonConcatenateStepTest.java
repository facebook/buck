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

package com.facebook.buck.json;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class JsonConcatenateStepTest {

  private JsonConcatenator jsonConcatenator;
  private Path mergedReport;
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws IOException {
    filesystem = new FakeProjectFilesystem();

    Path report1 = filesystem.resolve("report1");
    Path report2 = filesystem.resolve("report2");
    Path report3 = filesystem.resolve("report3");

    String content1 = "[{\"field_one\":1, \"field_two\":\"value\"}]";
    String content2 = "[]";
    String content3 =
        "[{\"field_one\":1, \"field_two\":\"value\"}, "
            + "{\"field_one\":7, \"field_two\":\"wow\"}]";

    filesystem.writeContentsToPath(content1, report1);
    filesystem.writeContentsToPath(content2, report2);
    filesystem.writeContentsToPath(content3, report3);

    ImmutableSortedSet<Path> reportsToMerge =
        ImmutableSortedSet.of(
            filesystem.resolve(report1), filesystem.resolve(report2), filesystem.resolve(report3));
    mergedReport = filesystem.resolve(Paths.get("finalReport"));
    jsonConcatenator = new JsonConcatenator(reportsToMerge, mergedReport, filesystem);
  }

  @Test
  public void testIsReportEmpty() {
    assertTrue("Should be empty", jsonConcatenator.isArrayEmpty("[]"));
    assertTrue("Should be empty", jsonConcatenator.isArrayEmpty("[ ]"));
    assertTrue("Should be empty", jsonConcatenator.isArrayEmpty("  [ ]   "));
    assertTrue("Should be empty", jsonConcatenator.isArrayEmpty("  \n[ \n\t \n]  \n"));
    assertFalse(
        "Should not be empty", jsonConcatenator.isArrayEmpty("[{\"a\":2,\"b\":\"aaa\nbbb\"}]"));
    assertFalse(
        "Should not be empty",
        jsonConcatenator.isArrayEmpty("[\n\n { \"a\":2,\"b\":\"aaa\nbbb\"  }\t]"));
  }

  @Test
  public void testStripArrayTokens() {
    String result = jsonConcatenator.stripArrayTokens("[]");
    assertThat("Should strip the surrounding square brackets", result, Matchers.equalTo(""));

    result = jsonConcatenator.stripArrayTokens("[ ]");
    assertThat("Should strip the surrounding square brackets", result, Matchers.equalTo(" "));

    result = jsonConcatenator.stripArrayTokens("  [ ]   ");
    assertThat("Should strip the surrounding square brackets", result, Matchers.equalTo(" "));

    result = jsonConcatenator.stripArrayTokens("  \n[ \n\n \n]  \n");
    assertThat(
        "Should strip the surrounding square brackets", result, Matchers.equalTo(" \n\n \n"));

    result = jsonConcatenator.stripArrayTokens("  \n[{\"a\":2,\"b\":\"aaa\nbbb\"}  ]  \n");
    assertThat(
        "Should strip the surrounding square brackets",
        result,
        Matchers.equalTo("{\"a\":2,\"b\":\"aaa\nbbb\"}  "));

    result = jsonConcatenator.stripArrayTokens("  \n[[{\"a\":2,\"b\":\"aaa\nbbb\"}]]  \n");
    assertThat(
        "Should strip the surrounding square brackets",
        result,
        Matchers.equalTo("[{\"a\":2,\"b\":\"aaa\nbbb\"}]"));
  }

  @Test
  public void testFinalizeWithEmptyReports() throws IOException {
    jsonConcatenator.initializeArray();
    jsonConcatenator.appendArray("[]");
    jsonConcatenator.appendArray("\n[\n\n ]\n");
    jsonConcatenator.appendArray(" \t [     ]  ");
    jsonConcatenator.finalizeArray();
    List<Object> bugs =
        ObjectMappers.createParser(filesystem.readFileIfItExists(mergedReport).get())
            .readValueAs(new TypeReference<List<Object>>() {});
    assertThat(
        "0 bugs expected in " + mergedReport + " not found", bugs.size(), Matchers.equalTo(0));
    String result = filesystem.readFileIfItExists(mergedReport).get();
    assertThat("Should be an empty array", result, Matchers.equalTo("[]"));
  }

  @Test
  public void testFinalizeWithOneReport() throws IOException {
    jsonConcatenator.initializeArray();
    jsonConcatenator.appendArray(
        "\n [{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ]\n ");
    jsonConcatenator.finalizeArray();
    List<Object> bugs =
        ObjectMappers.createParser(filesystem.readFileIfItExists(mergedReport).get())
            .readValueAs(new TypeReference<List<Object>>() {});
    assertThat(
        "1 bugs expected in " + mergedReport + " not found", bugs.size(), Matchers.equalTo(1));
    String result = filesystem.readFileIfItExists(mergedReport).get();
    assertThat(
        "Should be an array with one object",
        result,
        Matchers.equalTo(
            "[{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ]"));
  }

  @Test
  public void testFinalizeWithEmptyAndRegularReports() throws IOException {
    jsonConcatenator.initializeArray();
    jsonConcatenator.appendArray("\n[ ] \n\n");
    jsonConcatenator.appendArray(
        "\n [{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ]\n ");
    jsonConcatenator.appendArray("[]");
    jsonConcatenator.appendArray(
        "[{ \"file\":\"aFile.c\", \"type\":\"RESOURCE_LEAK\", \"line\":456}] ");
    jsonConcatenator.appendArray(
        "\n [{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":789 } ]\n ");
    jsonConcatenator.finalizeArray();
    List<Object> bugs =
        ObjectMappers.createParser(filesystem.readFileIfItExists(mergedReport).get())
            .readValueAs(new TypeReference<List<Object>>() {});
    assertThat(
        "3 bugs expected in " + mergedReport + " not found", bugs.size(), Matchers.equalTo(3));
    String result = filesystem.readFileIfItExists(mergedReport).get();
    assertThat(
        "Should be an array with three objects",
        result,
        Matchers.equalTo(
            "["
                + "{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":123} ,"
                + "{ \"file\":\"aFile.c\", \"type\":\"RESOURCE_LEAK\", \"line\":456},"
                + "{ \"file\":\"aFile.c\", \"type\":\"NULL_DEREFERENCE\", \"line\":789 } "
                + "]"));
  }

  @Test
  public void testMerge() throws IOException {
    jsonConcatenator.concatenate();
    List<Object> bugs =
        ObjectMappers.createParser(filesystem.readFileIfItExists(mergedReport).get())
            .readValueAs(new TypeReference<List<Object>>() {});
    assertThat(
        "3 bugs expected in " + mergedReport + " not found", bugs.size(), Matchers.equalTo(3));
  }
}
