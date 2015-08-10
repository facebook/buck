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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;

import javax.annotation.Nullable;

class InferMergeReportsStep implements Step {

  private ImmutableSortedSet<Path> reportFilesRelativeToProjectRoot;
  private Path destinationRelativeToProjectRoot;

  public InferMergeReportsStep(
      ImmutableSortedSet<Path> reportFilesRelativeToProjectRoot,
      Path destinationRelativeToProjectRoot) {
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
                return context.getProjectFilesystem().getRootPath().resolve(input);
              }
            })
        .toSortedSet(Ordering.natural());
    Path destination =
        context.getProjectFilesystem().getRootPath().resolve(destinationRelativeToProjectRoot);
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

  private class InferReportMerger {

    private ImmutableSortedSet<Path> reportsToMerge;
    private JsonWriter destinationWriter;
    private OutputStreamWriter destinationOutputStreamWriter;
    private BufferedWriter destinationBufferedWriter;
    private FileOutputStream destinationFileOutputStream;
    private Gson gson;
    private static final String JSON_ENCODING = "UTF-8";

    public InferReportMerger(
        ImmutableSortedSet<Path> reportsToMerge,
        Path destination) throws IOException {
      try {
        this.reportsToMerge = reportsToMerge;
        this.gson = new Gson();
        this.destinationFileOutputStream = new FileOutputStream(destination.toFile());
        this.destinationOutputStreamWriter = new OutputStreamWriter(
            destinationFileOutputStream, JSON_ENCODING);
        this.destinationBufferedWriter = new BufferedWriter(destinationOutputStreamWriter);
        this.destinationWriter = new JsonWriter(destinationBufferedWriter);
        this.destinationWriter.beginArray();
      } catch (IOException e) {
        closeAll();
        throw e;
      }
    }

    public void merge() throws IOException {
      for (Path report : reportsToMerge) {
        mergeReport(report);
      }
      closeAll();
    }

    private void closeAll() throws IOException {
      try {
        if (destinationWriter != null) { destinationWriter.endArray(); }
      } finally {
        if (destinationWriter != null) {
          destinationWriter.close();
        } else if (destinationBufferedWriter != null) {
          destinationBufferedWriter.close();
        } else if (destinationOutputStreamWriter != null) {
          destinationOutputStreamWriter.close();
        } else if (destinationFileOutputStream != null) {
          destinationFileOutputStream.close();
        }
      }
    }

    private void mergeReport(Path report) throws IOException {
      try (FileInputStream reportInputStream =
               new FileInputStream(report.toFile());
           InputStreamReader reportStreamReader =
               new InputStreamReader(reportInputStream, JSON_ENCODING);
           BufferedReader reportBufferedReader =
               new BufferedReader(reportStreamReader);
           JsonReader reportReader =
               new JsonReader(reportBufferedReader)) {
        reportReader.beginArray();
        while (reportReader.hasNext()) {
          gson.toJson(
              gson.fromJson(reportReader, Object.class),
              Object.class,
              destinationWriter);
        }
        reportReader.endArray();
      }
    }
  }
}
