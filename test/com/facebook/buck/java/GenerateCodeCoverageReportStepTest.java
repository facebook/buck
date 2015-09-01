/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class GenerateCodeCoverageReportStepTest {

  public static final String OUTPUT_DIRECTORY = Paths.get("buck-out/gen/output").toString();
  public static final Set<String> SOURCE_DIRECTORIES = ImmutableSet.of(
      MorePathsForTests.rootRelativePath("/absolute/path/to/parentDirectory1/src").toString(),
      MorePathsForTests.rootRelativePath("/absolute/path/to/parentDirectory2/src").toString());
  public static final Set<Path> CLASSES_DIRECTORIES = ImmutableSet.of(
      Paths.get("parentDirectory1/classes"), Paths.get("root/parentDirectory/classes"));

  private GenerateCodeCoverageReportStep step;
  private ExecutionContext context;

  @Before
  public void setUp() {
    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get("."));

    step = new GenerateCodeCoverageReportStep(
        filesystem.getRootPath(),
        SOURCE_DIRECTORIES,
        CLASSES_DIRECTORIES,
        Paths.get(OUTPUT_DIRECTORY),
        CoverageReportFormat.HTML,
        "TitleFoo");

    context = createMock(ExecutionContext.class);

    expect(
        context.getProjectFilesystem())
        .andReturn(filesystem)
        .anyTimes();
    replay(context);
  }

  @Test
  public void testGetShellCommandInternal() {
    ImmutableList.Builder<String> shellCommandBuilder = ImmutableList.builder();

    System.setProperty(
        "buck.report_generator_jar",
        MorePathsForTests.rootRelativePath("/absolute/path/to/report/generator/jar").toString());

    shellCommandBuilder.add(
        "java",
        "-jar",
        MorePathsForTests.rootRelativePath("/absolute/path/to/report/generator/jar").toString(),
        absolutifyPath(Paths.get(OUTPUT_DIRECTORY + "/parameters.properties")));

    List<String> expectedShellCommand = shellCommandBuilder.build();

    MoreAsserts.assertListEquals(expectedShellCommand, step.getShellCommand(context));
    verify(context);
  }

  @Test
  public void testSaveParametersToPropertyFile() throws IOException {
    byte[] actualOutput;
    try (ByteArrayOutputStream actualOutputStream = new ByteArrayOutputStream()) {
      step.saveParametersToPropertyStream(context.getProjectFilesystem(), actualOutputStream);

      actualOutput = actualOutputStream.toByteArray();
    }

    Properties actual = new Properties();
    try (ByteArrayInputStream actualInputStream = new ByteArrayInputStream(actualOutput)) {
      try (InputStreamReader reader = new InputStreamReader(actualInputStream)) {
        actual.load(reader);
      }
    }

    Properties expected = new Properties();
    expected.setProperty(
        "jacoco.output.dir",
        absolutifyPath(Paths.get(OUTPUT_DIRECTORY)));
    expected.setProperty("jacoco.exec.data.file", JUnitStep.JACOCO_EXEC_COVERAGE_FILE);
    expected.setProperty("jacoco.format", "html");
    expected.setProperty("jacoco.title", "TitleFoo");
    expected.setProperty(
        "classes.dir",
        String.format(
            "%s:%s",
            absolutifyPath(Paths.get("parentDirectory1/classes")),
            absolutifyPath(Paths.get("root/parentDirectory/classes"))));
    expected.setProperty(
        "src.dir",
        String.format(
            "%s:%s",
            MorePathsForTests.rootRelativePath(
                "/absolute/path/to/parentDirectory1/src").toString(),
            MorePathsForTests.rootRelativePath(
                "/absolute/path/to/parentDirectory2/src").toString()));

    assertEqual(expected, actual);
  }

  private static String absolutifyPath(Path relativePath) {
    return String.format(
        "%s%c%s",
        new File(".").getAbsoluteFile().toPath().normalize(),
        File.separatorChar,
        relativePath);
  }

  private static void assertEqual(Properties expected, Properties actual) {
    final Set<String> actualKeys = actual.stringPropertyNames();
    final Set<String> expectedKeys = expected.stringPropertyNames();

    assertEquals(expectedKeys, actualKeys);

    for (String key : expectedKeys) {
      assertEquals(expected.getProperty(key), actual.getProperty(key));
    }
  }
}
