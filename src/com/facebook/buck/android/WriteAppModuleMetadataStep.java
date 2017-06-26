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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

public class WriteAppModuleMetadataStep implements Step {
  private final Path metadataOutput;
  private final ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap;
  private final ProjectFilesystem filesystem;
  private final Optional<Path> proguardFullConfigFile;
  private final Optional<Path> proguardMappingFile;
  private final boolean skipProguard;

  public static final String CLASS_SECTION_HEADER = "CLASSES";
  public static final String MODULE_INDENTATION = "  ";
  public static final String CLASS_INDENTATION = "    ";

  private WriteAppModuleMetadataStep(
      Path metadataOutput,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      ProjectFilesystem filesystem,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      boolean skipProguard) {
    this.metadataOutput = metadataOutput;
    this.apkModuleToJarPathMap = apkModuleToJarPathMap;
    this.filesystem = filesystem;
    this.proguardFullConfigFile = proguardFullConfigFile;
    this.proguardMappingFile = proguardMappingFile;
    this.skipProguard = skipProguard;
  }

  public static WriteAppModuleMetadataStep writeModuleMetadata(
      Path metadataOut,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      ProjectFilesystem filesystem,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      boolean skipProguard) {
    return new WriteAppModuleMetadataStep(
        metadataOut,
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
      final ImmutableMultimap<APKModule, String> moduleToClassesMap =
          APKModuleGraph.getAPKModuleToClassesMap(
              apkModuleToJarPathMap, translatorFactory.createObfuscationFunction(), filesystem);

      // we need Module to classes map in order for testing:
      final Comparator<APKModule> moduleComparator =
          new Comparator<APKModule>() {

            @Override
            public int compare(APKModule left, APKModule right) {
              return left.getName().compareTo(right.getName());
            }
          };
      final TreeMultimap<APKModule, String> moduleToClassesOrderedMap =
          TreeMultimap.create(moduleComparator, Ordering.natural());
      moduleToClassesOrderedMap.putAll(moduleToClassesMap);

      // write class metadata
      ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
      metadataLines.add(CLASS_SECTION_HEADER);
      for (APKModule module : moduleToClassesOrderedMap.keySet()) {
        metadataLines.add(MODULE_INDENTATION + module.getName());
        for (String className : moduleToClassesOrderedMap.get(module)) {
          metadataLines.add(CLASS_INDENTATION + className);
        }
      }
      ImmutableList<String> moduleMetadata = metadataLines.build();
      filesystem.writeLinesToPath(moduleMetadata, metadataOutput);
      return StepExecutionResult.SUCCESS;
    } catch (IOException e) {
      context.logError(e, "There was an error running WriteAppModuleMetadataStep.");
      return StepExecutionResult.ERROR;
    }
  }

  @Override
  public String getShortName() {
    return "module_metadata";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "module_metadata";
  }
}
