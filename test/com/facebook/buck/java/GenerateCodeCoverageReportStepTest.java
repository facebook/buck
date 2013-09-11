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
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class GenerateCodeCoverageReportStepTest {

  @Test
  public void testGetShellCommandInternal() {
    Set<String> sourceDirectories = ImmutableSet.of(
        "parentDirectory1/src", "root/parentDirectory/src");
    String outputDirectory = "buck-out/gen/output";

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    ImmutableList.Builder<String> shellCommandBuilder = ImmutableList.builder();

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

    GenerateCodeCoverageReportStep step =
        new GenerateCodeCoverageReportStep(sourceDirectories, outputDirectory);

    MoreAsserts.assertListEquals(expectedShellCommand, step.getShellCommand(context));

    verify(context);
  }
}
