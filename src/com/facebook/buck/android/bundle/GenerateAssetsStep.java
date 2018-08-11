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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** This step generates a Assets.pb with java class files compiled from files.proto */
public class GenerateAssetsStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path output;
  private final ImmutableSet<Path> module;

  private static final Logger LOG = Logger.get(GenerateAssetsStep.class);

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
      File[] filesInModule = filesystem.getPathForRelativePath(assets).toFile().listFiles();
      if (filesInModule == null) {
        continue;
      }
      for (File file : filesInModule) {
        if (!file.getName().equals("assets")) {
          continue;
        }
        File[] filesInAssets = file.listFiles();
        if (filesInAssets == null) {
          continue;
        }
        for (File contentFile : filesInAssets) {
          processAssets(builder, contentFile, filesystem.getPathForRelativePath(assets));
        }
      }
    }
    return builder.build();
  }

  private void processAssets(Assets.Builder builder, File assetFile, Path root) {
    if (!assetFile.isDirectory()) {
      return;
    }
    File[] subdirectories = assetFile.listFiles();
    if (subdirectories == null || subdirectories.length < 1) {
      return;
    }
    Targeting.AbiTargeting.Builder abiTargetingBuilder = Targeting.AbiTargeting.newBuilder();
    addAbiTargeting(abiTargetingBuilder, assetFile);
    builder.addDirectory(
        TargetedAssetsDirectory.newBuilder()
            .setPath(root.relativize(assetFile.toPath()).toString())
            .setTargeting(AssetsDirectoryTargeting.newBuilder().setAbi(abiTargetingBuilder)));
    for (File subdirectory : subdirectories) {
      processAssets(builder, subdirectory, root);
    }
  }

  private void addAbiTargeting(Targeting.AbiTargeting.Builder builder, File assetFile) {
    File[] assetsDirectories = assetFile.listFiles();
    if (assetsDirectories == null) {
      return;
    }
    String fileWithInfo = assetFile.getName().equals("lib") ? "metadata.txt" : "libs.txt";
    for (File assetsDirectory : assetsDirectories) {
      if (!assetsDirectory.getName().equals(fileWithInfo)) {
        continue;
      }
      try {
        readFile(builder, assetsDirectory.toPath());
      } catch (IOException e) {
        String errorMessage =
            String.format("Fail to add Abi Targeting in the path %s", assetFile.toPath());
        LOG.error(e, errorMessage);
        throw new HumanReadableException(e, errorMessage);
      }
    }
  }

  private static void readFile(Targeting.AbiTargeting.Builder builder, Path fin)
      throws IOException {
    for (String line : Files.readAllLines(fin)) {
      builder.addValue(
          Targeting.Abi.newBuilder()
              .setAliasValue(GetAbiByName.getAbi(line.split(File.separator)[0])));
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
