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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class GenerateCodeCoverageReportStepTest {

  @Test
  public void testGetShellCommandInternal() {
    String outputDirectory = "buck-out/gen/output";
    Set<String> sourceDirectories = ImmutableSet.of(
        "/absolute/path/to/parentDirectory1/src", "/absolute/path/to/parentDirectory2/src");
    Set<Path> classesDirectories = ImmutableSet.of(
        Paths.get("parentDirectory1/classes"), Paths.get("root/parentDirectory/classes"));

    testJacocoReportGeneratorCommand(sourceDirectories, classesDirectories, outputDirectory);
  }

  private void testJacocoReportGeneratorCommand(
      Set<String> sourceDirectories,
      Set<Path> classesDirectories,
      String outputDirectory) {
    GenerateCodeCoverageReportStep step = new GenerateCodeCoverageReportStep(
        sourceDirectories, classesDirectories,
        Paths.get(outputDirectory), CoverageReportFormat.HTML);

    ExecutionContext context = createMock(ExecutionContext.class);
    expect(
        context.getProjectFilesystem())
        .andReturn(new ProjectFilesystem(Paths.get(".")))
        .anyTimes();
    replay(context);

    ImmutableList.Builder<String> shellCommandBuilder = ImmutableList.builder();

    System.setProperty("buck.report_generator_jar", "/absolute/path/to/report/generator/jar");

    shellCommandBuilder.add(
        "java",
        String.format("-Djacoco.output.dir=%s", outputDirectory),
        String.format("-Djacoco.exec.data.file=%s", JUnitStep.JACOCO_EXEC_COVERAGE_FILE),
        "-Djacoco.format=html",
        String.format("-Dclasses.dir=%s",
            String.format("%s/%s:%s/%s",
                new File(".").getAbsoluteFile().toPath().normalize(),
                "parentDirectory1/classes",
                new File(".").getAbsoluteFile().toPath().normalize(),
                "root/parentDirectory/classes")),
        String.format("-Dsrc.dir=%s",
            String.format("%s:%s",
                "/absolute/path/to/parentDirectory1/src",
                "/absolute/path/to/parentDirectory2/src")),
        "-jar", "/absolute/path/to/report/generator/jar");

    List<String> expectedShellCommand = shellCommandBuilder.build();

    MoreAsserts.assertListEquals(expectedShellCommand, step.getShellCommand(context));
    verify(context);
  }
}
