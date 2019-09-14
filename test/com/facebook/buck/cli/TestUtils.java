/*
 * Copyright 2019-present Facebook, Inc.
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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class TestUtils {

  static void assertBuildReport(
      ProjectWorkspace workspace, TemporaryPaths tmp, Path buildReportPath, String expectedFileName)
      throws IOException {
    assertTrue(Files.exists(buildReportPath));
    String randomNumberPlaceholder = "<RANDOM_NUMBER>";
    String extension = "sh";
    String fileSeparator = "/";
    if (Platform.detect() == Platform.WINDOWS) {
      extension = "cmd";
      fileSeparator = "\\\\";
    }
    String buildReportContents =
        new String(Files.readAllBytes(buildReportPath), Charsets.UTF_8)
            .replaceFirst(
                "genrule-\\d+\\." + extension,
                "genrule-" + randomNumberPlaceholder + "." + extension);

    String expectedResult =
        String.format(
                workspace.getFileContents(expectedFileName),
                (tmp.getRoot().toString()
                        + "/buck-out/tmp/genrule-"
                        + randomNumberPlaceholder
                        + "."
                        + extension)
                    .replace("/", File.separator)
                    .replace(File.separator, fileSeparator))
            .trim();
    assertEquals(expectedResult, buildReportContents);
  }
}
