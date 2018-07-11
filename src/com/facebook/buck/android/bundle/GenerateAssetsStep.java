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

import com.android.bundle.Files.Assets;
import com.android.bundle.Files.TargetedAssetsDirectory;
import com.android.bundle.Targeting;
import com.android.bundle.Targeting.AssetsDirectoryTargeting;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** This step generates a Assets.pb with java class files compiled from files.proto */
public class GenerateAssetsStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path output;
  private final ImmutableSet<Path> module;
  private final Set<String> allAvailableLanguages =
      new HashSet<>(Arrays.asList(Locale.getISOLanguages()));

  public GenerateAssetsStep(ProjectFilesystem filesystem, Path output, ImmutableSet<Path> module) {
    this.filesystem = filesystem;
    this.output = output;
    this.module = module;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Path path = filesystem.resolve(output);
    try (OutputStream outputFile = filesystem.newFileOutputStream(path)) {
      createAssets().writeTo(outputFile);
    }
    return StepExecutionResults.SUCCESS;
  }

  private Assets createAssets() {
    Assets.Builder builder = Assets.newBuilder();

    for (Path assets : module) {
      Targeting.LanguageTargeting.Builder languageTargetingBuilder =
          Targeting.LanguageTargeting.newBuilder();
      addLanguageTargeting(languageTargetingBuilder, assets.toString());
      builder.addDirectory(
          TargetedAssetsDirectory.newBuilder()
              .setPath(assets.toString())
              .setTargeting(
                  AssetsDirectoryTargeting.newBuilder().setLanguage(languageTargetingBuilder)));
    }
    return builder.build();
  }

  private void addLanguageTargeting(Targeting.LanguageTargeting.Builder builder, String path) {
    String[] parts = path.split(File.separator);
    String[] potentialLanguage = parts[1].split("-");
    if (potentialLanguage.length < 2) {
      return;
    }
    if (allAvailableLanguages.contains(potentialLanguage[1])) {
      builder.addValue(potentialLanguage[1]);
    }
  }

  @Override
  public String getShortName() {
    return "generate Assets protocol buffer";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "";
  }
}
