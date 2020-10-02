/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidKotlinCoreArg;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.facebook.buck.features.project.intellij.targetinfo.HashFile;
import com.facebook.buck.features.project.intellij.targetinfo.TargetInfo;
import com.facebook.buck.features.project.intellij.targetinfo.TargetInfoBinaryFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TargetInfoMapManager {
  static final String TARGET_INFO_MAP_FILENAME = "target-info.json";
  static final String TARGET_INFO_BINARY_FILENAME = "target-info.bin";
  static final String TARGET_INFO_MODULE_TO_TARGET_FILENAME = "target-info-modules.bin";

  static final String INTELLIJ_TYPE = "intellij.type";
  static final String INTELLIJ_NAME = "intellij.name";
  static final String INTELLIJ_FILE_PATH = "intellij.file_path";
  static final String GENERATED_SOURCES = "generated_sources";
  static final String MODULE_LANG = "module.lang";
  static final String MODULE_TYPE = "module";
  static final String BUCK_TYPE = "buck.type";
  static final String LIBRARY_TYPE = "library";
  static final String MODULE_LIBRARY_TYPE = "module_library";

  private final TargetGraph targetGraph;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem outFilesystem;
  private final Path targetInfoMapPath;
  private final Map<String, Map<String, Object>> targetInfoMap;

  public TargetInfoMapManager(
      TargetGraph targetGraph,
      IjProjectConfig projectConfig,
      ProjectFilesystem outFilesystem,
      boolean isUpdate) {
    this.targetGraph = targetGraph;
    this.projectConfig = projectConfig;
    this.outFilesystem = outFilesystem;
    this.targetInfoMapPath =
        projectConfig.getProjectPaths().getIdeaConfigDir().resolve(TARGET_INFO_MAP_FILENAME);
    this.targetInfoMap = readTargetInfoMap(targetInfoMapPath, outFilesystem, isUpdate);
  }

  private static Map<String, Map<String, Object>> readTargetInfoMap(
      Path targetInfoMapPath, ProjectFilesystem outFilesystem, boolean isUpdate) {
    if (isUpdate && outFilesystem.exists(targetInfoMapPath)) {
      try {
        return ObjectMappers.createParser(outFilesystem.newFileInputStream(targetInfoMapPath))
            .readValueAs(new TypeReference<TreeMap<String, TreeMap<String, Object>>>() {});
      } catch (IOException ignored) {
      }
    }
    return Maps.newTreeMap();
  }

  public boolean isProjectLibrary(String name) {
    return targetInfoMap
        .getOrDefault(name, Collections.emptyMap())
        .getOrDefault(INTELLIJ_TYPE, "")
        .equals(LIBRARY_TYPE);
  }

  @VisibleForTesting
  String readTargetInfoMapAsString() throws IOException {
    return String.join(System.lineSeparator(), outFilesystem.readLines(targetInfoMapPath));
  }

  public void write(
      ImmutableSet<IjModule> modules,
      ImmutableSet<IjLibrary> libraries,
      BuckOutPathConverter buckOutPathConverter,
      IJProjectCleaner cleaner)
      throws IOException {
    writeModuleTargetInfo(modules, buckOutPathConverter);
    writeLibraryTargetInfo(libraries);
    try (JsonGenerator generator =
        ObjectMappers.createGenerator(outFilesystem.newFileOutputStream(targetInfoMapPath))
            .useDefaultPrettyPrinter()) {
      generator.writeObject(targetInfoMap);
      cleaner.doNotDelete(targetInfoMapPath);
    }
  }

  private TargetInfo convertToTargetInfo(Map<String, Object> data) {
    TargetInfo targetInfo = new TargetInfo();
    targetInfo.intellijFilePath = (String) data.get(INTELLIJ_FILE_PATH);
    targetInfo.intellijName = (String) data.get(INTELLIJ_NAME);
    targetInfo.moduleLanguage =
        data.get(MODULE_LANG) == null
            ? null
            : TargetInfo.ModuleLanguage.valueOf((String) data.get(MODULE_LANG));
    targetInfo.ruleType =
        data.get(BUCK_TYPE) == null
            ? null
            : TargetInfo.BuckType.valueOf((String) data.get(BUCK_TYPE));
    targetInfo.intellijType =
        data.get(INTELLIJ_TYPE) == null
            ? null
            : TargetInfo.IntelliJType.valueOf((String) data.get(INTELLIJ_TYPE));
    if (data.get(GENERATED_SOURCES) != null) {
      targetInfo.generatedSources =
          ((List<?>) data.get(GENERATED_SOURCES))
              .stream().map(o -> o.toString()).collect(Collectors.toList());
    }

    return targetInfo;
  }

  /** Write binary info. */
  public void writeBinaryInfo(
      ImmutableSet<IjModule> modules,
      ImmutableSet<IjLibrary> libraries,
      BuckOutPathConverter buckOutPathConverter,
      IJProjectCleaner cleaner)
      throws IOException {
    writeModuleTargetInfo(modules, buckOutPathConverter);
    writeLibraryTargetInfo(libraries);

    // TODO: use TargetInfo everywhere.
    Map<String, TargetInfo> converted = new HashMap<String, TargetInfo>();
    Map<String, String> modulesToTargets = new HashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : targetInfoMap.entrySet()) {
      TargetInfo info = convertToTargetInfo(entry.getValue());
      converted.put(entry.getKey(), info);
      modulesToTargets.put(info.intellijName, entry.getKey());
    }

    Path binaryFilePath =
        projectConfig.getProjectPaths().getIdeaConfigDir().resolve(TARGET_INFO_BINARY_FILENAME);
    new TargetInfoBinaryFile(binaryFilePath).write(converted);
    cleaner.doNotDelete(binaryFilePath);

    // Write a mapping of module names -> targets
    Path modulesToTargetsName =
        projectConfig
            .getProjectPaths()
            .getIdeaConfigDir()
            .resolve(TARGET_INFO_MODULE_TO_TARGET_FILENAME);
    new HashFile<String, String>(
            HashFile.STRING_SERIALIZER, HashFile.STRING_SERIALIZER, modulesToTargetsName)
        .write(modulesToTargets);
    cleaner.doNotDelete(modulesToTargetsName);
  }

  private void writeModuleTargetInfo(
      ImmutableSet<IjModule> modules, BuckOutPathConverter buckOutPathConverter) {
    modules.forEach(
        module -> {
          Map<BuildTarget, List<IjFolder>> targetsToGeneratedSourcesMap =
              module.getTargetsToGeneratedSourcesMap();
          module
              .getTargets()
              .forEach(
                  target -> {
                    Map<String, Object> targetInfo = Maps.newTreeMap();
                    targetInfo.put(INTELLIJ_TYPE, MODULE_TYPE);
                    targetInfo.put(INTELLIJ_NAME, module.getName());
                    targetInfo.put(
                        INTELLIJ_FILE_PATH,
                        projectConfig
                            .getProjectPaths()
                            .getModuleImlFilePath(module, projectConfig)
                            .toString());
                    targetInfo.put(BUCK_TYPE, getRuleNameForBuildTarget(target));
                    if (targetsToGeneratedSourcesMap.containsKey(target)) {
                      Function<IjFolder, Path> pathTransformer;
                      if (buckOutPathConverter.hasBuckOutPathForGeneratedProjectFiles()) {
                        pathTransformer = folder -> buckOutPathConverter.convert(folder.getPath());
                      } else {
                        pathTransformer = IjFolder::getPath;
                      }
                      targetInfo.put(
                          GENERATED_SOURCES,
                          ImmutableList.sortedCopyOf(
                              targetsToGeneratedSourcesMap.get(target).stream()
                                  .map(pathTransformer)
                                  .collect(Collectors.toList())));
                    }
                    getAndroidModuleLang(target)
                        .ifPresent(
                            moduleLang -> targetInfo.put(MODULE_LANG, moduleLang.toString()));
                    targetInfoMap.put(target.getFullyQualifiedName(), targetInfo);
                  });
        });
  }

  private void writeLibraryTargetInfo(ImmutableSet<IjLibrary> libraries) {
    libraries.forEach(
        library -> {
          library
              .getTargets()
              .forEach(
                  target -> {
                    Map<String, Object> targetInfo = Maps.newTreeMap();
                    targetInfo.put(
                        INTELLIJ_TYPE,
                        library.getLevel() == IjLibrary.Level.PROJECT
                            ? LIBRARY_TYPE
                            : MODULE_LIBRARY_TYPE);
                    targetInfo.put(INTELLIJ_NAME, library.getName());
                    targetInfo.put(
                        INTELLIJ_FILE_PATH,
                        projectConfig
                            .getProjectPaths()
                            .getLibraryXmlFilePath(library, projectConfig)
                            .toString());
                    targetInfo.put(BUCK_TYPE, getRuleNameForBuildTarget(target));
                    targetInfoMap.put(target.getFullyQualifiedName(), targetInfo);
                  });
        });
  }

  private String getRuleNameForBuildTarget(BuildTarget buildTarget) {
    return targetGraph.get(buildTarget).getRuleType().getName();
  }

  private Optional<AndroidLibraryDescription.JvmLanguage> getAndroidModuleLang(
      BuildTarget buildTarget) {
    return Optional.of(targetGraph.get(buildTarget).getConstructorArg())
        .filter(arg -> arg instanceof AndroidKotlinCoreArg)
        .map(AndroidKotlinCoreArg.class::cast)
        .flatMap(AndroidKotlinCoreArg::getLanguage);
  }
}
