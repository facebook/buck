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

package com.facebook.buck.util.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.facebook.buck.util.trace.BuildTraces.TraceAttributes;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class BuildTracesTest {

  @Test
  public void testGetTraceAttributesForId() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem(FakeClock.doNotCare());
    projectFilesystem.writeContentsToPath(
        "[\n"
            + "  {\n"
            + "    \"cat\": \"buck\",\n"
            + "    \"pid\": 0,\n"
            + "    \"ts\": 0,\n"
            + "    \"ph\": \"M\",\n"
            + "    \"args\": {\n"
            + "      \"name\": \"buck\"\n"
            + "    },\n"
            + "    \"name\": \"process_name\",\n"
            + "    \"tid\": 0\n"
            + "  },\n"
            + "  {\n"
            + "    \"cat\": \"buck\",\n"
            + "    \"name\": \"build\",\n"
            + "    \"ph\": \"B\",\n"
            + "    \"pid\": 0,\n"
            + "    \"tid\": 1,\n"
            + "    \"ts\": 5621911884918,\n"
            + "    \"args\": {\n"
            + "      \"command_args\": \"buck\"\n"
            + "    }\n"
            + "  }\n"
            + "]",
        projectFilesystem.getBuckPaths().getTraceDir().resolve("build.a.trace"));

    BuildTraces helper = new BuildTraces(projectFilesystem);
    TraceAttributes traceAttributes = helper.getTraceAttributesFor("a");
    assertEquals(
        "BuildTraces should be able to extract the command.",
        Optional.of("buck build buck"),
        traceAttributes.getCommand());
    assertEquals(
        FileTime.fromMillis(FakeClock.doNotCare().currentTimeMillis()),
        traceAttributes.getLastModifiedTime());

    // We cannot verify the contents of getFormattedDateTime() because they may vary depending on
    // timezone and locale.
    assertNotNull(Strings.emptyToNull(traceAttributes.getFormattedDateTime()));
  }

  @Test
  public void testGetTraceAttributesForJsonWithoutName() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem(FakeClock.doNotCare());
    projectFilesystem.writeContentsToPath(
        "["
            + "{"
            + "\"cat\":\"buck\","
            + "\"ph\":\"B\","
            + "\"pid\":0,"
            + "\"tid\":1,"
            + "\"ts\":5621911884918,"
            + "\"args\":{\"command_args\":\"buck\"}"
            + "}"
            + "]",
        projectFilesystem.getBuckPaths().getTraceDir().resolve("build.b.trace"));

    BuildTraces helper = new BuildTraces(projectFilesystem);
    TraceAttributes traceAttributes = helper.getTraceAttributesFor("b");
    assertEquals(
        "BuildTraces should not be able to extract the command because there is no name "
            + "attribute.",
        Optional.empty(),
        traceAttributes.getCommand());
    assertEquals(
        FileTime.fromMillis(FakeClock.doNotCare().currentTimeMillis()),
        traceAttributes.getLastModifiedTime());
  }

  @Test
  public void testGetTraceAttributesForJsonWithoutCommandArgs() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem(FakeClock.doNotCare());
    projectFilesystem.writeContentsToPath(
        "["
            + "{"
            + "\"cat\":\"buck\","
            + "\"ph\":\"B\","
            + "\"pid\":0,"
            + "\"tid\":1,"
            + "\"ts\":5621911884918"
            + "}"
            + "]",
        projectFilesystem.getBuckPaths().getTraceDir().resolve("build.c.trace"));

    BuildTraces helper = new BuildTraces(projectFilesystem);
    TraceAttributes traceAttributes = helper.getTraceAttributesFor("c");
    assertEquals(
        "BuildTraces should not be able to extract the command because there is no "
            + "command_args attribute.",
        Optional.empty(),
        traceAttributes.getCommand());
    assertEquals(
        FileTime.fromMillis(FakeClock.doNotCare().currentTimeMillis()),
        traceAttributes.getLastModifiedTime());
  }

  @Test
  public void testSortByLastModified() throws IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem(clock);
    clock.setCurrentTimeMillis(1);
    Path traceDir = projectFilesystem.getBuckPaths().getTraceDir();
    projectFilesystem.touch(traceDir.resolve("build.1.trace"));
    clock.setCurrentTimeMillis(4);
    projectFilesystem.touch(traceDir.resolve("build.4.trace"));
    clock.setCurrentTimeMillis(2);
    projectFilesystem.touch(traceDir.resolve("build.2.trace"));
    clock.setCurrentTimeMillis(5);
    projectFilesystem.touch(traceDir.resolve("build.5.trace"));
    clock.setCurrentTimeMillis(3);
    projectFilesystem.touch(traceDir.resolve("build.3.trace"));
    projectFilesystem.touch(traceDir.resolve("build.3b.trace"));

    BuildTraces helper = new BuildTraces(projectFilesystem);
    assertEquals(
        ImmutableList.of(
            traceDir.resolve("build.5.trace"),
            traceDir.resolve("build.4.trace"),
            traceDir.resolve("build.3b.trace"),
            traceDir.resolve("build.3.trace"),
            traceDir.resolve("build.2.trace"),
            traceDir.resolve("build.1.trace")),
        helper.listTraceFilesByLastModified());
  }

  @Test(expected = HumanReadableException.class)
  public void testInputsForTracesThrowsWhenEmpty() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem(FakeClock.doNotCare());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTraceDir());
    BuildTraces helper = new BuildTraces(projectFilesystem);
    helper.getInputsForTraces("nonexistent");
  }

  @Test(expected = HumanReadableException.class)
  public void testTraceAttributesThrowsWhenEmpty() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem(FakeClock.doNotCare());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTraceDir());
    BuildTraces helper = new BuildTraces(projectFilesystem);
    helper.getTraceAttributesFor("nonexistent");
  }

  @Test
  public void testFindingTracesInNewPerCommandDirectories() throws IOException {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    FakeProjectFilesystem fs = new FakeProjectFilesystem(clock);
    fs.touch(getNewTraceFilePath(fs, "build", "1", 1));
    fs.touch(getNewTraceFilePath(fs, "audit", "4", 4));
    fs.touch(getNewTraceFilePath(fs, "query", "2", 2));
    fs.touch(getNewTraceFilePath(fs, "targets", "5", 5));
    fs.touch(getNewTraceFilePath(fs, "test", "3", 3));
    fs.touch(getNewTraceFilePath(fs, "test", "3b", 3));

    BuildTraces helper = new BuildTraces(fs);
    assertEquals(
        ImmutableList.of(
            fs.getBuckPaths().getLogDir().resolve("1970-01-01_00h00m05s_targets_5/build.5.trace"),
            fs.getBuckPaths().getLogDir().resolve("1970-01-01_00h00m04s_audit_4/build.4.trace"),
            fs.getBuckPaths().getLogDir().resolve("1970-01-01_00h00m03s_test_3b/build.3b.trace"),
            fs.getBuckPaths().getLogDir().resolve("1970-01-01_00h00m03s_test_3/build.3.trace"),
            fs.getBuckPaths().getLogDir().resolve("1970-01-01_00h00m02s_query_2/build.2.trace"),
            fs.getBuckPaths().getLogDir().resolve("1970-01-01_00h00m01s_build_1/build.1.trace")),
        helper.listTraceFilesByLastModified());
  }

  private static Path getNewTraceFilePath(
      ProjectFilesystem fs, String commandName, String buildId, int seconds) {
    InvocationInfo info =
        InvocationInfo.of(
                new BuildId(buildId),
                false,
                false,
                commandName,
                ImmutableList.of(),
                ImmutableList.of(),
                fs.getBuckPaths().getLogDir())
            .withTimestampMillis(TimeUnit.SECONDS.toMillis(seconds));
    return info.getLogDirectoryPath().resolve("build." + buildId + ".trace");
  }
}
