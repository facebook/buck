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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.util.Set;
import java.nio.file.Path;

public class GenerateCodeCoverageReportStep extends ShellStep {

  @VisibleForTesting
  static final String REPORT_OUTPUT_DIR = "report.out.dir";

  @VisibleForTesting
  static final ImmutableSet<String> CODE_COVERAGE_OUTPUT_FORMAT =
      ImmutableSet.of("html", "xml", "txt");

  private final Set<String> srcDirectories;
  private final Set<Path> classesDirectories;
  private final String outputDirectory;

  public GenerateCodeCoverageReportStep(Set<String> srcDirectories,
      Set<Path> classesDirectories,
      String outputDirectory) {
    this.srcDirectories = ImmutableSet.copyOf(srcDirectories);
    this.classesDirectories = ImmutableSet.copyOf(classesDirectories);
    this.outputDirectory = outputDirectory;
  }

  @Override
  public String getShortName() {
    return String.format("emma_report");
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    // In practice, we have seen EMMA OOM with the following stacktrace, so we increase the heap to
    // protect against this failure:
    //
    // Exception in thread "main" com.vladium.emma.EMMARuntimeException: unexpected failure:
    //   at com.vladium.emma.Command.exit(Command.java:237)
    //   at com.vladium.emma.report.reportCommand.run(reportCommand.java:145)
    //   at emma.main(emma.java:40)
    // Caused by: java.lang.OutOfMemoryError: Java heap space
    //   at java.util.HashMap.<init>(HashMap.java:283)
    //   at java.util.HashMap.<init>(HashMap.java:297)
    //   at com.vladium.emma.data.MetaData.readExternal(MetaData.java:223)
    //   at com.vladium.emma.data.DataFactory.readEntry(DataFactory.java:770)
    //   at com.vladium.emma.data.DataFactory.mergeload(DataFactory.java:461)
    //   at com.vladium.emma.data.DataFactory.load(DataFactory.java:56)
    //   at com.vladium.emma.report.ReportProcessor._run(ReportProcessor.java:175)
    //   at com.vladium.emma.Processor.run(Processor.java:54)
    //   at com.vladium.emma.report.reportCommand.run(reportCommand.java:130)
    //   ... 1 more
    if (!context.isJacocoEnabled()) {
      args.add("-Xmx1024M");

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
    } else {
      args.add("-classpath",
          String.format("%s/*:%s/../report-generator-build/",
              JUnitStep.PATH_TO_JACOCO_JARS, JUnitStep.PATH_TO_JACOCO_JARS));

      args.add(String.format("-Djacoco.output.dir=%s", outputDirectory));

      args.add(String.format("-Djacoco.exec.data.file=%s", JUnitStep.JACOCO_EXEC_COVERAGE_FILE));

      args.add(String.format("-Dclasses.dir=%s",
          Joiner.on(":").join(Iterables.transform(classesDirectories,
              context.getProjectFilesystem().getAbsolutifier()))));

      args.add(String.format("-Dsrc.dir=%s", "src"));

      // Generate report from JaCoCo exec file using
      // 'third-party/java/jacoco-0.6.4/report-generator-src/ReportGenerator.java'
      args.add("ReportGenerator");
    }

    return args.build();
  }
}
