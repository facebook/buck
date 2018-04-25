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

package com.facebook.buck.testutil.integration;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Splitter;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.ini4j.Ini;

/**
 * Utility to locate the {@code testdata} directory relative to an integration test.
 *
 * <p>Integration tests will often have a sample project layout in a directory under a directory
 * named {@code testdata}. The name of the directory should correspond to the scenario being tested.
 */
public class TestDataHelper {

  private static final Splitter JAVA_PACKAGE_SPLITTER = Splitter.on('.');

  private static final String TEST_DIRECTORY = new File("test").getAbsolutePath();

  private static final String TESTDATA_DIRECTORY_NAME = "testdata";

  /** Utility class: do not instantiate. */
  private TestDataHelper() {}

  public static Path getTestDataDirectory(Object testCase) {
    return getTestDataDirectory(testCase.getClass());
  }

  public static Path getTestDataDirectory(Class<?> testCaseClass) {
    String javaPackage = testCaseClass.getPackage().getName();
    List<String> parts = new ArrayList<>();
    for (String component : JAVA_PACKAGE_SPLITTER.split(javaPackage)) {
      parts.add(component);
    }

    parts.add(TESTDATA_DIRECTORY_NAME);
    String[] directories = parts.toArray(new String[0]);
    Path result = FileSystems.getDefault().getPath(TEST_DIRECTORY, directories);

    // If we're running this test in IJ, then this path doesn't exist. Fall back to one that does
    if (!Files.exists(result)) {
      result =
          Paths.get("test")
              .resolve(testCaseClass.getPackage().getName().replace('.', '/'))
              .resolve("testdata");
    }
    return result;
  }

  public static Path getTestDataScenario(Object testCase, String scenario) {
    return getTestDataDirectory(testCase).resolve(scenario);
  }

  public static ProjectWorkspace createProjectWorkspaceForScenario(
      Object testCase, String scenario, TemporaryPaths temporaryRoot) {
    Path templateDir = TestDataHelper.getTestDataScenario(testCase, scenario);
    return new ProjectWorkspace(templateDir, temporaryRoot.getRoot());
  }

  public static ProjectWorkspace createProjectWorkspaceForScenario(
      Object testCase, String scenario, Path temporaryRoot) {
    Path templateDir = TestDataHelper.getTestDataScenario(testCase, scenario);
    return new ProjectWorkspace(templateDir, temporaryRoot);
  }

  public static ProjectWorkspace createProjectWorkspaceForScenarioWithoutDefaultCell(
      Object testCase, String scenario, TemporaryPaths temporaryRoot) {
    Path templateDir = TestDataHelper.getTestDataScenario(testCase, scenario);
    return new ProjectWorkspace(templateDir, temporaryRoot.getRoot(), false);
  }

  public static ProjectWorkspace createProjectWorkspaceForScenarioWithoutDefaultCell(
      Object testCase, String scenario, Path temporaryRoot) {
    Path templateDir = TestDataHelper.getTestDataScenario(testCase, scenario);
    return new ProjectWorkspace(templateDir, temporaryRoot, false);
  }

  public static void overrideBuckconfig(
      ProjectWorkspace projectWorkspace,
      Map<String, ? extends Map<String, String>> buckconfigOverrides)
      throws IOException {
    String config = projectWorkspace.getFileContents(".buckconfig");
    Ini ini = new Ini(new StringReader(config));
    for (Map.Entry<String, ? extends Map<String, String>> section :
        buckconfigOverrides.entrySet()) {
      for (Map.Entry<String, String> entry : section.getValue().entrySet()) {
        ini.put(section.getKey(), entry.getKey(), entry.getValue());
      }
    }
    StringWriter writer = new StringWriter();
    ini.store(writer);
    Files.write(projectWorkspace.getPath(".buckconfig"), writer.toString().getBytes(UTF_8));
  }
}
