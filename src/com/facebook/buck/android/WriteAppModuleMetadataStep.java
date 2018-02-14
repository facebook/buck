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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;

public class WriteAppModuleMetadataStep implements Step {
  private final Path metadataOutput;
  private final ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap;
  private final APKModuleGraph apkModuleGraph;
  private final ProjectFilesystem filesystem;
  private final Optional<Path> proguardFullConfigFile;
  private final Optional<Path> proguardMappingFile;
  private final boolean skipProguard;

  public static final String CLASS_SECTION_HEADER = "CLASSES";
  public static final String DEPS_SECTION_HEADER = "DEPS";
  public static final String MODULE_INDENTATION = "  ";
  public static final String ITEM_INDENTATION = "    ";

  private WriteAppModuleMetadataStep(
      Path metadataOutput,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      APKModuleGraph apkModuleGraph,
      ProjectFilesystem filesystem,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      boolean skipProguard) {
    this.metadataOutput = metadataOutput;
    this.apkModuleToJarPathMap = apkModuleToJarPathMap;
    this.apkModuleGraph = apkModuleGraph;
    this.filesystem = filesystem;
    this.proguardFullConfigFile = proguardFullConfigFile;
    this.proguardMappingFile = proguardMappingFile;
    this.skipProguard = skipProguard;
  }

  public static WriteAppModuleMetadataStep writeModuleMetadata(
      Path metadataOut,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      APKModuleGraph apkModuleGraph,
      ProjectFilesystem filesystem,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      boolean skipProguard) {
    return new WriteAppModuleMetadataStep(
        metadataOut,
        apkModuleToJarPathMap,
        apkModuleGraph,
        filesystem,
        proguardFullConfigFile,
        proguardMappingFile,
        skipProguard);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      // Get module to classes map in sorted order for build determinism and testing
      ProguardTranslatorFactory translatorFactory =
          ProguardTranslatorFactory.create(
              filesystem, proguardFullConfigFile, proguardMappingFile, skipProguard);
      final ImmutableMultimap<APKModule, String> moduleToClassesMap =
          APKModuleGraph.getAPKModuleToClassesMap(
              apkModuleToJarPathMap, translatorFactory.createObfuscationFunction(), filesystem);
      final TreeMultimap<APKModule, String> orderedModuleToClassesMap =
          sortModuleToStringsMultimap(moduleToClassesMap);

      // Module to module deps map is already sorted
      final SortedMap<APKModule, ? extends SortedSet<APKModule>> moduleToDepsMap =
          apkModuleGraph.toOutgoingEdgesMap();

      // Write metdata lines to output
      final LinkedList<String> metadataLines = new LinkedList<>();
      metadataLines.add(CLASS_SECTION_HEADER);
      writeModuleToStringsMultimap(orderedModuleToClassesMap, metadataLines);
      metadataLines.add(DEPS_SECTION_HEADER);
      writeModuleToModulesMap(moduleToDepsMap, metadataLines);
      filesystem.writeLinesToPath(metadataLines, metadataOutput);

      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      context.logError(e, "There was an error running WriteAppModuleMetadataStep.");
      return StepExecutionResults.ERROR;
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

  private static TreeMultimap<APKModule, String> sortModuleToStringsMultimap(
      ImmutableMultimap<APKModule, String> multimap) {
    final TreeMultimap<APKModule, String> orderedMap =
        TreeMultimap.create(
            (left, right) -> left.getName().compareTo(right.getName()), Ordering.natural());
    orderedMap.putAll(multimap);
    return orderedMap;
  }

  private void writeModuleToStringsMultimap(
      Multimap<APKModule, String> map, Collection<String> dest) throws IOException {
    for (APKModule dexStore : map.keySet()) {
      dest.add(MODULE_INDENTATION + dexStore.getName());
      for (String item : map.get(dexStore)) {
        dest.add(ITEM_INDENTATION + item);
      }
    }
  }

  private void writeModuleToModulesMap(
      Map<APKModule, ? extends Iterable<APKModule>> map, Collection<String> dest)
      throws IOException {
    for (Map.Entry<APKModule, ? extends Iterable<APKModule>> entry : map.entrySet()) {
      dest.add(MODULE_INDENTATION + entry.getKey().getName());
      for (APKModule item : entry.getValue()) {
        dest.add(ITEM_INDENTATION + item.getName());
      }
    }
  }
}
