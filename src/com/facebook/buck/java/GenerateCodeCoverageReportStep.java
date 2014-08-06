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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Set;

public class GenerateCodeCoverageReportStep extends ShellStep {

  @VisibleForTesting
  static final String PATH_TO_ASM_JAR = System.getProperty(
      "buck.path_to_asm_jar",
      "third-party/java/asm/asm-debug-all-4.1.jar");

  private final Set<Path> classesDirectories;
  private final Path outputDirectory;
  private CoverageReportFormat format;

  public GenerateCodeCoverageReportStep(
      Set<Path> classesDirectories,
      Path outputDirectory,
      CoverageReportFormat format) {
    this.classesDirectories = ImmutableSet.copyOf(classesDirectories);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.format = Preconditions.checkNotNull(format);
  }

  @Override
  public String getShortName() {
    return String.format("emma_report");
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    args.add("-classpath",
        String.format("%s:%s/*:%s/../report-generator-build/",
            PATH_TO_ASM_JAR, JUnitStep.PATH_TO_JACOCO_JARS, JUnitStep.PATH_TO_JACOCO_JARS));

    args.add(String.format("-Djacoco.output.dir=%s", outputDirectory));

    args.add(String.format("-Djacoco.exec.data.file=%s", JUnitStep.JACOCO_EXEC_COVERAGE_FILE));

    args.add(String.format("-Djacoco.format=%s", format.toString().toLowerCase()));

    args.add(String.format("-Dclasses.dir=%s",
        Joiner.on(":").join(Iterables.transform(classesDirectories,
            context.getProjectFilesystem().getAbsolutifier()))));

    args.add(String.format("-Dsrc.dir=%s", "src"));

    // Generate report from JaCoCo exec file using
    // 'third-party/java/jacoco-0.6.4/report-generator-src/ReportGenerator.java'
    args.add("ReportGenerator");

    return args.build();
  }
}
