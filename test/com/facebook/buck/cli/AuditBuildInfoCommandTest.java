/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class AuditBuildInfoCommandTest {

  private static final String BUCK_BINARY_HASH = "binaryHash";
  private static final String BUCK_BUILD_COMMIT_ID = "commitId";
  private static final String BUCK_BUILD_COMMIT_TIMESTAMP = "commitTimestamp";
  private static final String BUCK_BUILD_IS_DIRTY = "isDirty";

  private TestConsole console;

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();

    System.setProperty("buck.binary_hash", BUCK_BINARY_HASH);
    System.setProperty("buck.git_commit", BUCK_BUILD_COMMIT_ID);
    System.setProperty("buck.git_commit_timestamp", BUCK_BUILD_COMMIT_TIMESTAMP);
    System.setProperty("buck.git_dirty", BUCK_BUILD_IS_DIRTY);
  }

  @Test
  public void testBuildInfoPrintedInJsonFormat() throws IOException {
    AuditBuildInfoCommand.collectAndDumpBuildInformation(console, Collections.emptyList(), true);
    String output = console.getTextWrittenToStdOut();

    JsonNode jsonNode = ObjectMappers.READER.readTree(output);

    assertEquals(BUCK_BINARY_HASH, jsonNode.get("buck_binary_hash").textValue());
    assertEquals(BUCK_BUILD_COMMIT_ID, jsonNode.get("buck_build_commit_id").textValue());
    assertEquals(
        BUCK_BUILD_COMMIT_TIMESTAMP, jsonNode.get("buck_build_commit_timestamp").textValue());
    assertEquals(BUCK_BUILD_IS_DIRTY, jsonNode.get("buck_build_is_dirty").textValue());
  }

  @Test
  public void testOneBuildInfoFieldPrintedInJsonFormat() throws IOException {
    AuditBuildInfoCommand.collectAndDumpBuildInformation(
        console, Collections.singleton("buck_build_commit_timestamp"), true);
    String output = console.getTextWrittenToStdOut();

    JsonNode jsonNode = ObjectMappers.READER.readTree(output);

    assertEquals(
        BUCK_BUILD_COMMIT_TIMESTAMP, jsonNode.get("buck_build_commit_timestamp").textValue());
    assertEquals(1, jsonNode.size());
  }

  @Test
  public void testBuildInfoPrintedInPlainFormat() {
    AuditBuildInfoCommand.collectAndDumpBuildInformation(console, Collections.emptyList(), false);
    String output = console.getTextWrittenToStdOut().trim();

    Map<String, String> outputFields =
        Splitter.on("\n").trimResults().withKeyValueSeparator(" = ").split(output);

    assertEquals(BUCK_BINARY_HASH, outputFields.get("buck_binary_hash"));
    assertEquals(BUCK_BUILD_COMMIT_ID, outputFields.get("buck_build_commit_id"));
    assertEquals(BUCK_BUILD_COMMIT_TIMESTAMP, outputFields.get("buck_build_commit_timestamp"));
    assertEquals(BUCK_BUILD_IS_DIRTY, outputFields.get("buck_build_is_dirty"));
  }

  @Test
  public void testOneBuildInfoFieldPrintedInPlainFormat() {
    AuditBuildInfoCommand.collectAndDumpBuildInformation(
        console, Collections.singleton("buck_build_commit_timestamp"), false);
    String output = console.getTextWrittenToStdOut().trim();

    Map<String, String> outputFields =
        Splitter.on("\n").trimResults().withKeyValueSeparator(" = ").split(output);

    assertEquals(BUCK_BUILD_COMMIT_TIMESTAMP, outputFields.get("buck_build_commit_timestamp"));
    assertEquals(1, outputFields.size());
  }

  @Test
  public void testInvalidBuildInfoFieldNameShowsError() {
    try {
      AuditBuildInfoCommand.collectAndDumpBuildInformation(
          console, Collections.singleton("some_field"), false);
    } catch (IllegalArgumentException e) {
      assertEquals("Unknown field: some_field", e.getMessage());
    }
  }
}
