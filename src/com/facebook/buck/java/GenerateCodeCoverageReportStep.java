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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.CoverageReportFormat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

public class GenerateCodeCoverageReportStep extends ShellStep {

  private final ProjectFilesystem filesystem;
  private final Set<String> sourceDirectories;
  private final Set<Path> classesDirectories;
  private final Path outputDirectory;
  private CoverageReportFormat format;
  private final String title;
  private final Path propertyFile;

  public GenerateCodeCoverageReportStep(
      ProjectFilesystem filesystem,
      Set<String> sourceDirectories,
      Set<Path> classesDirectories,
      Path outputDirectory,
      CoverageReportFormat format,
      String title) {
    super(filesystem.getRootPath());

    this.filesystem = filesystem;
    this.sourceDirectories = ImmutableSet.copyOf(sourceDirectories);
    this.classesDirectories = ImmutableSet.copyOf(classesDirectories);
    this.outputDirectory = outputDirectory;
    this.format = format;
    this.title = title;
    this.propertyFile = outputDirectory.resolve("parameters.properties");
  }

  @Override
  public String getShortName() {
    return "emma_report";
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    try (OutputStream propertyFileStream = new FileOutputStream(propertyFile.toFile())){
      saveParametersToPropertyStream(filesystem, propertyFileStream);
    } catch (IOException e) {
      context.logError(e, "Cannot write coverage report generator params to file.");
      return 1;
    }

    return super.execute(context);
  }

  @VisibleForTesting
  void saveParametersToPropertyStream(
      ProjectFilesystem filesystem,
      OutputStream outputStream) throws IOException {
    final Properties properties = new Properties();

    properties.setProperty(
        "jacoco.output.dir",
        filesystem.getAbsolutifier().apply(outputDirectory).toString());
    properties.setProperty("jacoco.exec.data.file", JUnitStep.JACOCO_EXEC_COVERAGE_FILE);
    properties.setProperty("jacoco.format", format.toString().toLowerCase());
    properties.setProperty("jacoco.title", title);
    properties.setProperty(
        "classes.dir", Joiner.on(":").join(
            Iterables.transform(
                classesDirectories,
                filesystem.getAbsolutifier())));
    properties.setProperty("src.dir", Joiner.on(":").join(sourceDirectories));

    try (Writer writer = new OutputStreamWriter(outputStream, "utf8")) {
      properties.store(writer, "Parameters for Jacoco report generator.");
    }
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    // Generate report from JaCoCo exec file using 'ReportGenerator.java'

    args.add("-jar", System.getProperty("buck.report_generator_jar"));
    args.add(filesystem.getAbsolutifier().apply(propertyFile).toString());

    return args.build();
  }

}
