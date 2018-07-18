/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.bundle;

import com.android.bundle.Config.BundleConfig;
import com.android.bundle.Config.Bundletool;
import com.android.bundle.Config.Compression;
import com.android.bundle.Config.Optimizations;
import com.android.bundle.Config.SplitDimension;
import com.android.bundle.Config.SplitsConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * This step creates a BundleConfig.pb file with java class files generated from compiling
 * config.proto.
 */
public class GenerateBundleConfigStep implements Step {

  private final ProjectFilesystem projectFilesystem;
  private final Path output;

  public GenerateBundleConfigStep(ProjectFilesystem projectFilesystem, Path output) {
    this.projectFilesystem = projectFilesystem;
    this.output = output;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Path path = projectFilesystem.resolve(this.output);
    try (OutputStream outputFile = projectFilesystem.newFileOutputStream(path)) {
      createDefaultBundleConfig().writeTo(outputFile);
    }
    return StepExecutionResults.SUCCESS;
  }

  /**
   * This method creates a BuildConfig with all fields built upon default setting
   *
   * @return BundleConfig built with default setting
   */
  public static BundleConfig createDefaultBundleConfig() {
    Optimizations.Builder optimizations = Optimizations.newBuilder();
    SplitsConfig.Builder splitsConfig = SplitsConfig.newBuilder();
    for (int i = 1; i <= 3; i++) {
      splitsConfig.addSplitDimension(
          SplitDimension.newBuilder().setValueValue(i).setNegate(i != 1).build());
    }

    optimizations.setSplitsConfig(splitsConfig.build());

    return BundleConfig.newBuilder()
        .setBundletool(Bundletool.newBuilder().build())
        .setOptimizations(optimizations.build())
        .setCompression(Compression.newBuilder().build())
        .build();
  }

  @Override
  public String getShortName() {
    return "generate bundle config protocol buffer";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "";
  }
}
