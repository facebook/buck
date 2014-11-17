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

package com.facebook.buck.zip;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class UnzipStepTest {

  @Test
  public void testGetShortName() {
    Path zipFile = Paths.get("the/zipfile.zip");
    Path outputDirectory = Paths.get("an/output/dir");
    UnzipStep unzipStep = new UnzipStep(zipFile, outputDirectory);
    assertEquals("unzip", unzipStep.getShortName());
  }

  @Test
  public void testGetShellCommand() {
    Path zipFile = Paths.get("the/zipfile.zip");
    Path outputDirectory = Paths.get("an/output/dir");
    UnzipStep unzipStep = new UnzipStep(zipFile, outputDirectory);

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem() {
      @Override
      public Path resolve(Path relativePath) {
        return Paths.get("/abs/path").resolve(relativePath);
      }
    };
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    assertEquals(
        "unzip /abs/path/the/zipfile.zip -d /abs/path/an/output/dir",
        unzipStep.getDescription(executionContext));
  }
}
