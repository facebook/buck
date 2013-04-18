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

package com.facebook.buck.shell;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class GenerateCodeCoverageReportCommandTest {

  @Test
  public void testGetShellCommandInternal() {
    Set<String> sourceDirectories = ImmutableSet.of(
        "parentDirectory1/src", "root/parentDirectory/src");
    String outputDirectory = "buck-gen/output";

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    List<String> expectedShellCommand = ImmutableList.of(
        "java",
        "-classpath", JUnitCommand.PATH_TO_EMMA_JAR,
        "emma", "report",
        String.format("-D%s=%s",
            GenerateCodeCoverageReportCommand.REPORT_OUTPUT_DIR, outputDirectory),
        "-report", GenerateCodeCoverageReportCommand.CODE_COVERAGE_OUTPUT_FORMAT,
        "-input", String.format("%s/coverage.ec,%s/coverage.em",
            JUnitCommand.EMMA_OUTPUT_DIR, JUnitCommand.EMMA_OUTPUT_DIR),
        "-sourcepath", "parentDirectory1/src,root/parentDirectory/src");

    GenerateCodeCoverageReportCommand command =
        new GenerateCodeCoverageReportCommand(sourceDirectories, outputDirectory);

    MoreAsserts.assertListEquals(expectedShellCommand, command.getShellCommandInternal(context));

    verify(context);
  }
}
