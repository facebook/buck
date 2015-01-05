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

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

/**
 * Utility to locate the {@code testdata} directory relative to an integration test.
 * <p>
 * Integration tests will often have a sample project layout in a directory under a directory named
 * {@code testdata}. The name of the directory should correspond to the scenario being tested.
 */
public class TestDataHelper {

  private static final Splitter JAVA_PACKAGE_SPLITTER = Splitter.on('.');

  private static final String TEST_DIRECTORY = new File("test").getAbsolutePath();

  private static final String TESTDATA_DIRECTORY_NAME = "testdata";

  /** Utility class: do not instantiate. */
  private TestDataHelper() {}

  public static Path getTestDataDirectory(Object testCase) {
    String javaPackage = testCase.getClass().getPackage().getName();
    List<String> parts = Lists.newArrayList();
    for (String component : JAVA_PACKAGE_SPLITTER.split(javaPackage)) {
      parts.add(component);
    }

    parts.add(TESTDATA_DIRECTORY_NAME);
    String[] directories = parts.toArray(new String[0]);
    return FileSystems.getDefault().getPath(TEST_DIRECTORY, directories);
  }

  public static Path getTestDataScenario(Object testCase, String scenario) {
    return getTestDataDirectory(testCase).resolve(scenario);
  }

  public static ProjectWorkspace createProjectWorkspaceForScenario(
      Object testCase,
      String scenario,
      DebuggableTemporaryFolder temporaryFolder) {
    Path templateDir = TestDataHelper.getTestDataScenario(testCase, scenario);
    return new ProjectWorkspace(templateDir, temporaryFolder);
  }
}
