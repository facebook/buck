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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.CoverageReportFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Set;

public class GenerateCodeCoverageReportStep extends ShellStep {

  private final Set<String> sourceDirectories;
  private final Set<Path> classesDirectories;
  private final Path outputDirectory;
  private CoverageReportFormat format;
  private final String title;

  public GenerateCodeCoverageReportStep(
      Set<String> sourceDirectories,
      Set<Path> classesDirectories,
      Path outputDirectory,
      CoverageReportFormat format,
      String title) {
    this.sourceDirectories = ImmutableSet.copyOf(sourceDirectories);
    this.classesDirectories = ImmutableSet.copyOf(classesDirectories);
    this.outputDirectory = outputDirectory;
    this.format = format;
    this.title = title;
  }

  @Override
  public String getShortName() {
    return String.format("emma_report");
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    args.add(String.format("-Djacoco.output.dir=%s", outputDirectory));

    args.add(String.format("-Djacoco.exec.data.file=%s", JUnitStep.JACOCO_EXEC_COVERAGE_FILE));

    args.add(String.format("-Djacoco.format=%s", format.toString().toLowerCase()));

    args.add(String.format("-Djacoco.title=%s", title));

    args.add(String.format("-Dclasses.dir=%s",
        Joiner.on(":").join(Iterables.transform(classesDirectories,
            context.getProjectFilesystem().getAbsolutifier()))));

    args.add(String.format("-Dsrc.dir=%s", Joiner.on(":").join(sourceDirectories)));

    // Generate report from JaCoCo exec file using 'ReportGenerator.java'

    args.add("-jar", System.getProperty("buck.report_generator_jar"));

    return args.build();
  }
}
