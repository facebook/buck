/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;

import javax.annotation.Nullable;

class InferMergeReportsStep implements Step {

  private final ProjectFilesystem filesystem;
  private ImmutableSortedSet<Path> reportFilesRelativeToProjectRoot;
  private Path destinationRelativeToProjectRoot;

  public InferMergeReportsStep(
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> reportFilesRelativeToProjectRoot,
      Path destinationRelativeToProjectRoot) {
    this.filesystem = filesystem;
    this.reportFilesRelativeToProjectRoot = reportFilesRelativeToProjectRoot;
    this.destinationRelativeToProjectRoot = destinationRelativeToProjectRoot;
  }

  @Override
  public int execute(final ExecutionContext context) throws IOException, InterruptedException {
    ImmutableSortedSet<Path> reportsToMerge = FluentIterable.from(reportFilesRelativeToProjectRoot)
        .transform(
            new Function<Path, Path>() {
              @Nullable
              @Override
              public Path apply(Path input) {
                return filesystem.getRootPath().resolve(input);
              }
            })
        .toSortedSet(Ordering.natural());
    Path destination =
        filesystem.getRootPath().resolve(destinationRelativeToProjectRoot);
    new InferReportMerger(reportsToMerge, destination).merge();
    return 0;
  }

  @Override
  public String getShortName() {
    return "merge-reports";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Merge Infer Reports";
  }

  @VisibleForTesting
  class InferReportMerger {

    private ImmutableSortedSet<Path> reportsToMerge;

    private OutputStreamWriter destinationOutputStreamWriter;
    private BufferedWriter destinationBufferedWriter;
    private FileOutputStream destinationFileOutputStream;
    private boolean stillEmpty;


    @VisibleForTesting
    static final String JSON_ENCODING = "UTF-8";

    public InferReportMerger(
        ImmutableSortedSet<Path> reportsToMerge,
        Path destination) throws IOException {
      this.reportsToMerge = reportsToMerge;
      this.stillEmpty = true;
      try {
        this.destinationFileOutputStream = new FileOutputStream(destination.toFile());
        this.destinationOutputStreamWriter = new OutputStreamWriter(
            destinationFileOutputStream, JSON_ENCODING);
        this.destinationBufferedWriter = new BufferedWriter(destinationOutputStreamWriter);
      } catch (IOException e) {
        closeAll();
        throw e;
      }
    }

    private void closeAll() throws IOException {
      if (destinationBufferedWriter != null) {
        destinationBufferedWriter.close();
      } else if (destinationOutputStreamWriter != null) {
        destinationOutputStreamWriter.close();
      } else if (destinationFileOutputStream != null) {
        destinationFileOutputStream.close();
      }
    }

    public void merge() throws IOException {
      try {
        initializeReport();
        for (Path report : reportsToMerge) {
          appendReport(loadJsonReportFromFile(report));
        }
      } finally {
        finalizeReport();
      }
    }

    @VisibleForTesting
    void initializeReport() throws IOException {
      destinationBufferedWriter.write("[");
    }

    @VisibleForTesting
    void appendReport(String report) throws IOException {
      if (isReportEmpty(report)) {
        return;
      }
      if (!stillEmpty) {
        destinationBufferedWriter.write(",");
      }
      destinationBufferedWriter.write(stripArrayTokens(report));
      stillEmpty = false;
    }

    @VisibleForTesting
    void finalizeReport() throws IOException {
      try {
        destinationBufferedWriter.write("]");
      } finally {
        closeAll();
      }
    }

    private String loadJsonReportFromFile(Path report) throws IOException {
      Optional<String> result = filesystem.readFileIfItExists(report);
      if (result.isPresent()) {
        return result.get();
      } else {
        throw new IOException("Error loading " + report.toString());
      }
    }

    @VisibleForTesting
    boolean isReportEmpty(String report) {
      return report.matches("(\\s)*\\[(\\s)*\\](\\s)*");
    }
    @VisibleForTesting
    String stripArrayTokens(String report) {
      return report.replaceAll("^(\\s)*\\[", "").replaceAll("\\](\\s)*$", "");
    }


  }
}
