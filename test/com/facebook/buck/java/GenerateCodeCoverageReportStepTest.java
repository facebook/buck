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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GenerateCodeCoverageReportStepTest {

  @Test
  public void testGetShellCommandInternal() {
    Set<String> sourceDirectories = ImmutableSet.of(
        "parentDirectory1/src", "root/parentDirectory/src");
    String outputDirectory = "buck-out/gen/output";
    Set<Path> classesDirectories = ImmutableSet.of(
        Paths.get("parentDirectory1/classes"), Paths.get("root/parentDirectory/classes"));

    testJacocoReportGeneratorCommand(sourceDirectories, classesDirectories, outputDirectory);
    testEmmaCommand(sourceDirectories, classesDirectories, outputDirectory);
  }

  private void testEmmaCommand(Set<String> sourceDirectories,
      Set<Path> classesDirectories,
      String outputDirectory) {
    GenerateCodeCoverageReportStep step = new GenerateCodeCoverageReportStep(
        sourceDirectories, classesDirectories, Paths.get(outputDirectory));

    ExecutionContext context = createMock(ExecutionContext.class);
    expect(context.isJacocoEnabled()).andReturn(false).times(1);
    replay(context);

    ImmutableList.Builder<String> shellCommandBuilder = ImmutableList.builder();

    shellCommandBuilder = ImmutableList.builder();

    shellCommandBuilder.add(
        "java",
        "-Xmx1024M",
        "-classpath", JUnitStep.PATH_TO_EMMA_JAR,
        "emma", "report",
        String.format("-D%s=%s",
            GenerateCodeCoverageReportStep.REPORT_OUTPUT_DIR, outputDirectory));

    for (String reportFormat : GenerateCodeCoverageReportStep.CODE_COVERAGE_OUTPUT_FORMAT) {
      shellCommandBuilder.add(
          "-report", reportFormat
      );
    }

    shellCommandBuilder.add(
        "-input",
        String.format(
            "%s/coverage.ec,%s/coverage.em",
            JUnitStep.EMMA_OUTPUT_DIR, JUnitStep.EMMA_OUTPUT_DIR),
        "-sourcepath",
        "parentDirectory1/src,root/parentDirectory/src");

    List<String> expectedShellCommand = shellCommandBuilder.build();

    expectedShellCommand = shellCommandBuilder.build();

    MoreAsserts.assertListEquals(expectedShellCommand, step.getShellCommand(context));
    verify(context);
  }

  private void testJacocoReportGeneratorCommand(Set<String> sourceDirectories,
      Set<Path> classesDirectories,
      String outputDirectory) {
    GenerateCodeCoverageReportStep step = new GenerateCodeCoverageReportStep(
        sourceDirectories, classesDirectories, Paths.get(outputDirectory));

    ExecutionContext context = createMock(ExecutionContext.class);
    expect(context.isJacocoEnabled()).andReturn(true).times(1);
    expect(context.getProjectFilesystem()).andReturn(new ProjectFilesystem(new File("."))).anyTimes();
    replay(context);

    ImmutableList.Builder<String> shellCommandBuilder = ImmutableList.builder();

    shellCommandBuilder.add(
        "java",
        "-classpath",String.format("%s/*:%s/../report-generator-build/",
        JUnitStep.PATH_TO_JACOCO_JARS,JUnitStep.PATH_TO_JACOCO_JARS),
        String.format("-Djacoco.output.dir=%s",outputDirectory),
        String.format("-Djacoco.exec.data.file=%s",JUnitStep.JACOCO_EXEC_COVERAGE_FILE),
        String.format("-Dclasses.dir=%s",String.format("%s/%s:%s/%s",
            new File(".").getAbsoluteFile().toPath().normalize(),
            "parentDirectory1/classes",
            new File(".").getAbsoluteFile().toPath().normalize(),
            "root/parentDirectory/classes")),
        String.format("-Dsrc.dir=%s", "src"),
        "ReportGenerator");

    List<String> expectedShellCommand = shellCommandBuilder.build();

    MoreAsserts.assertListEquals(expectedShellCommand, step.getShellCommand(context));
    verify(context);
  }
}
