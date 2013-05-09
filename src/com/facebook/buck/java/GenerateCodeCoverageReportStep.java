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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class GenerateCodeCoverageReportStep extends ShellStep {

  @VisibleForTesting
  static final String REPORT_OUTPUT_DIR = "report.out.dir";

  @VisibleForTesting
  static final ImmutableSet<String> CODE_COVERAGE_OUTPUT_FORMAT =
      ImmutableSet.of("html", "xml", "txt");

  private final Set<String> srcDirectories;
  private final String outputDirectory;

  public GenerateCodeCoverageReportStep(Set<String> srcDirectories, String outputDirectory) {
    this.srcDirectories = ImmutableSet.copyOf(srcDirectories);
    this.outputDirectory = outputDirectory;
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return String.format("emma report -Doutput.dir=%s", JUnitStep.EMMA_OUTPUT_DIR);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    args.add("-classpath", JUnitStep.PATH_TO_EMMA_JAR);

    args.add("emma", "report");

    // Add output directory property so code coverage data lands in the specified output directory.
    args.add(String.format("-D%s=%s", REPORT_OUTPUT_DIR, outputDirectory));

    for (String reportFormat : CODE_COVERAGE_OUTPUT_FORMAT) {
      args.add("-report", reportFormat);
    }

    // Specify the paths to the runtime code coverage data and the metadata files.
    // coverage.ec: EMMA runtime code coverage data.
    // coverage.em: EMMA metadata.
    args.add("-input",
        String.format("%s/coverage.ec,%s/coverage.em",
            JUnitStep.EMMA_OUTPUT_DIR, JUnitStep.EMMA_OUTPUT_DIR));

    // Specify the source path so we can see from source file which lines of code are tested.
    String sourcepathArg = Joiner.on(",").join(srcDirectories);
    args.add("-sourcepath").add(sourcepathArg);

    return args.build();
  }
}
