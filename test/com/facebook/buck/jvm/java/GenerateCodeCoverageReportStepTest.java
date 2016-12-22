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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public class  GenerateCodeCoverageReportStepTest {

  public static final String OUTPUT_DIRECTORY = Paths.get("buck-out/gen/output").toString();
  public static final Set<String> SOURCE_DIRECTORIES = ImmutableSet.of(
      MorePathsForTests.rootRelativePath("/absolute/path/to/parentDirectory1/src").toString(),
      MorePathsForTests.rootRelativePath("/absolute/path/to/parentDirectory2/src").toString());

  private GenerateCodeCoverageReportStep step;
  private ExecutionContext context;
  private ProjectFilesystem filesystem;
  private Set<Path> jarFiles;

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    filesystem = new ProjectFilesystem(Paths.get(".").toAbsolutePath());

    jarFiles = new HashSet<>();
    File jarFile = new File(tmp.getRoot(), "foo.jar");
    File jar2File = new File(tmp.getRoot(), "foo2.jar");
    try (InputStream jarIn = getClass()
         .getResourceAsStream("testdata/code_coverage_test/foo.jar")) {
      Files.copy(jarIn, jarFile.toPath());
    }

    try (InputStream jarIn = getClass()
         .getResourceAsStream("testdata/code_coverage_test/foo.jar")) {
      Files.copy(jarIn, jar2File.toPath());
    }

    jarFiles.add(jarFile.toPath());
    jarFiles.add(jar2File.toPath());

    assertTrue(jarFile.exists());

    step = new GenerateCodeCoverageReportStep(
        new ExternalJavaRuntimeLauncher("/baz/qux/java"),
        filesystem,
        SOURCE_DIRECTORIES,
        jarFiles,
        Paths.get(OUTPUT_DIRECTORY),
        CoverageReportFormat.HTML,
        "TitleFoo",
        Optional.empty(),
        Optional.empty());

    context = TestExecutionContext.newInstance();
  }

  @Test
  public void testGetShellCommandInternal() {
    ImmutableList.Builder<String> shellCommandBuilder = ImmutableList.builder();

    System.setProperty(
        "buck.report_generator_jar",
        MorePathsForTests.rootRelativePath("/absolute/path/to/report/generator/jar").toString());

    shellCommandBuilder.add(
        "/baz/qux/java",
        "-jar",
        MorePathsForTests.rootRelativePath("/absolute/path/to/report/generator/jar").toString(),
        absolutifyPath(Paths.get(OUTPUT_DIRECTORY + "/parameters.properties")));

    List<String> expectedShellCommand = shellCommandBuilder.build();

    MoreAsserts.assertListEquals(expectedShellCommand, step.getShellCommand(context));
  }

  @Test
  public void testJarFileIsExtracted() throws Throwable {
    final File[] extractedDir = new File[2];
    step = new GenerateCodeCoverageReportStep(
        new ExternalJavaRuntimeLauncher("/baz/qux/java"),
        filesystem,
        SOURCE_DIRECTORIES,
        jarFiles,
        Paths.get(OUTPUT_DIRECTORY),
        CoverageReportFormat.HTML,
        "TitleFoo",
        Optional.empty(),
        Optional.empty()) {
        @Override
        StepExecutionResult executeInternal(
            ExecutionContext context,
            Set<Path> jarFiles) {
          for (int i = 0; i < 2; i++) {
            extractedDir[i] = new ArrayList<>(jarFiles).get(i).toFile();
            assertTrue(extractedDir[i].isDirectory());
            assertTrue(
                new File(extractedDir[i], "com/facebook/testing/coverage/Foo.class")
                .exists());
          }
          return null;
        }
      };

    step.execute(TestExecutionContext.newInstance());
    assertFalse(extractedDir[0].exists());
    assertFalse(extractedDir[1].exists());
  }


  @Test
  public void testSaveParametersToPropertyFile() throws IOException {
    byte[] actualOutput;
    Set<Path> directories = ImmutableSet.of(Paths.get("foo/bar"), Paths.get("foo/bar2"));
    try (ByteArrayOutputStream actualOutputStream = new ByteArrayOutputStream()) {
      step.saveParametersToPropertyStream(filesystem, directories, actualOutputStream);

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
    expected.setProperty("jacoco.exec.data.file", "jacoco.exec");
    expected.setProperty("jacoco.format", "html");
    expected.setProperty("jacoco.title", "TitleFoo");
    expected.setProperty(
        "classes.dir",
        String.format(
            "%s:%s",
            absolutifyPath(Paths.get("foo/bar")),
            absolutifyPath(Paths.get("foo/bar2"))));
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
