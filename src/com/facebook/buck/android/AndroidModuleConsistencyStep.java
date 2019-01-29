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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class AndroidModuleConsistencyStep implements Step {
  private final Path metadataInput;
  private final ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap;
  private final ProjectFilesystem filesystem;
  Optional<Path> proguardFullConfigFile;
  Optional<Path> proguardMappingFile;
  boolean skipProguard;

  private AndroidModuleConsistencyStep(
      Path metadataInput,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      ProjectFilesystem filesystem,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      boolean skipProguard) {
    this.metadataInput = metadataInput;
    this.apkModuleToJarPathMap = apkModuleToJarPathMap;
    this.filesystem = filesystem;
    this.proguardFullConfigFile = proguardFullConfigFile;
    this.proguardMappingFile = proguardMappingFile;
    this.skipProguard = skipProguard;
  }

  public static AndroidModuleConsistencyStep ensureModuleConsistency(
      Path metadataIn,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      ProjectFilesystem filesystem,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      boolean skipProguard) {
    return new AndroidModuleConsistencyStep(
        metadataIn,
        apkModuleToJarPathMap,
        filesystem,
        proguardFullConfigFile,
        proguardMappingFile,
        skipProguard);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      ProguardTranslatorFactory translatorFactory =
          ProguardTranslatorFactory.create(
              filesystem, proguardFullConfigFile, proguardMappingFile, skipProguard);
      ImmutableMultimap<String, APKModule> classToModuleMap =
          APKModuleGraph.getAPKModuleToClassesMap(
                  apkModuleToJarPathMap, translatorFactory.createObfuscationFunction(), filesystem)
              .inverse();

      ImmutableMap<String, String> classToModuleResult =
          AppModularityMetadataUtil.getClassToModuleMap(filesystem, metadataInput);

      boolean hasError = false;
      StringBuilder errorMessage = new StringBuilder();

      for (Map.Entry<String, String> entry : classToModuleResult.entrySet()) {
        String className = entry.getKey();
        String resultName = entry.getValue();
        if (classToModuleMap.containsKey(className)) {
          // if the new classMap does not contain the old class, that's fine,
          // consistency is only broken when a class changes to a different module.
          APKModule module = classToModuleMap.get(className).iterator().next();
          if (!module.getName().equals(resultName)) {
            hasError = true;
            errorMessage
                .append(className)
                .append(" moved from App Module: ")
                .append(resultName)
                .append(" to App Module: ")
                .append(module.getName())
                .append("\n");
          }
        }
      }

      if (hasError) {
        throw new IllegalStateException(errorMessage.toString());
      }

      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      context.logError(e, "There was an error running AndroidModuleConsistencyStep.");
      return StepExecutionResults.ERROR;
    } catch (IllegalStateException e) {
      context.logError(e, "There was an error running AndroidModuleConsistencyStep.");
      return StepExecutionResults.ERROR;
    }
  }

  @Override
  public String getShortName() {
    return "module_consistency";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "module_consistency";
  }
}
