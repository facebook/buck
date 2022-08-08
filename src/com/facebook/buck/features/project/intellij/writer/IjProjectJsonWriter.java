/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.project.intellij.writer;

import com.facebook.buck.features.project.intellij.BuckOutPathConverter;
import com.facebook.buck.features.project.intellij.IJProjectCleaner;
import com.facebook.buck.features.project.intellij.IjLibraryNameConflictResolver;
import com.facebook.buck.features.project.intellij.IjProjectTemplateDataPreparer;
import com.facebook.buck.features.project.intellij.IntellijModulesListParser;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Writes the serialized representations of IntelliJ project components to disk in JSON format. */
public class IjProjectJsonWriter extends IjProjectWriter {
  private static final ObjectMapper mapper = new ObjectMapper();

  public IjProjectJsonWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem,
      IntellijModulesListParser modulesParser,
      IJProjectCleaner cleaner,
      ProjectFilesystem outFilesystem,
      BuckOutPathConverter buckOutPathConverter,
      IjLibraryNameConflictResolver nameConflictResolver) {
    super(
        projectDataPreparer,
        projectConfig,
        projectFilesystem,
        modulesParser,
        cleaner,
        outFilesystem,
        buckOutPathConverter,
        nameConflictResolver);
  }

  @Override
  protected boolean writeModule(IjModule module) throws IOException {

    ModuleData moduleData = prepareModuleToBeWritten(module);

    JsonMap jsonMap =
        new JsonMap()
            .put("type", module.getModuleType().getImlModuleType())
            .put("sdk", module.getModuleType().getSdkName(projectConfig).orElse(null))
            .put("languageLevel", getLanguageLevel(module))
            .put("sdkType", module.getModuleType().getSdkType(projectConfig))
            .put("metaInfDirectory", getMetaInfDirectory(module));

    if (((boolean) moduleData.getAndroidProperties().get("enabled"))) {
      jsonMap.put("androidProperties", moduleData.getAndroidProperties());
    }
    if (!moduleData.getGeneratedFolders().isEmpty()) {
      jsonMap.put(
          "generatedFolders", JsonBuilder.buildGeneratedFolders(moduleData.getGeneratedFolders()));
    }
    if (!moduleData.getContentRoots().isEmpty()) {
      jsonMap.put("contentRoots", JsonBuilder.buildContentRoots(moduleData.getContentRoots()));
    }
    if (!moduleData.getDependencies().isEmpty()) {
      jsonMap.put("dependencies", JsonBuilder.buildDependencies(moduleData.getDependencies()));
    }

    moduleInfoManager.addModuleInfo(
        module.getName(),
        projectPaths.getModuleDir(module).toString(),
        moduleData.getDependencies(),
        moduleData.getContentRoots());

    return writeTemplate(
        jsonMap.get(), projectPaths.getModuleFilePath(module, projectConfig, ".json"));
  }

  @Override
  protected void writeProjectSettings() throws IOException {
    Optional<String> sdkName = projectConfig.getProjectJdkName();
    Optional<String> sdkType = projectConfig.getProjectJdkType();

    if (!sdkName.isPresent() || !sdkType.isPresent()) {
      return;
    }
    String languageLevelInIjFormat = getLanguageLevelFromConfig();
    JsonMap map =
        new JsonMap()
            .put("languageLevel", languageLevelInIjFormat)
            .put("jdk15", getJdk15FromLanguageLevel(languageLevelInIjFormat))
            .put("jdkName", sdkName.get())
            .put("jdkType", sdkType.get())
            .put("outputUrl", projectConfig.getOutputUrl().orElse(null));

    writeTemplate(map.get(), getIdeaConfigDir().resolve("misc.json"));
  }

  @Override
  protected void writeLibrary(IjLibrary library) throws IOException {
    library = prepareLibraryToBeWritten(library, null);
    JsonMap map = new JsonMap().put("name", library.getName()).put("type", library.getType());
    JsonBuilder.addLibraryFields(library, map);

    Path path =
        projectPaths.getLibraryXmlFilePath(library, projectConfig, nameConflictResolver, ".json");
    writeTemplate(map.get(), path);
  }

  // Write modules.json
  @Override
  protected void writeModulesIndex(ImmutableSortedSet<ModuleIndexEntry> moduleEntries)
      throws IOException {

    List<Object> modEntryList =
        moduleEntries.stream()
            .map(
                modEntry ->
                    new JsonMap()
                        .put("fileUrl", modEntry.getFileUrl())
                        .put(
                            "filePath",
                            modEntry.getFilePath() != null ? modEntry.getFilePath().toString() : "")
                        .put("group", modEntry.getGroup())
                        .get())
            .collect(Collectors.toList());

    JsonMap map = new JsonMap().put("moduleEntries", modEntryList);
    writeTemplate(map.get(), getIdeaConfigDir().resolve("modules.json"));
  }

  @Override
  protected void writeWorkspace() throws IOException {
    Path workspacePath = getIdeaConfigDir().resolve("workspace.json");
    JsonMap map =
        new JsonMap()
            .put(
                "project",
                new JsonMap()
                    .put("version", "4")
                    .put(
                        "component",
                        new JsonMap()
                            .put("name", "ChangeListManager")
                            .put(
                                "option",
                                new JsonMap().put("EXCLUDED_CONVERTED_TO_IGNORED", "true").get())
                            .get())
                    .get());

    writeTemplate(map.get(), workspacePath);
    cleaner.doNotDelete(workspacePath);
  }

  /**
   * Writes template to output project filesystem
   *
   * @param path Relative path from project root
   */
  private boolean writeTemplate(Object contents, Path path) throws IOException {
    byte[] renderedContentsBytes =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(contents).getBytes();

    boolean didUpdate = writeToFile(outFilesystem, renderedContentsBytes, path, getIdeaConfigDir());
    cleaner.doNotDelete(path);
    return didUpdate;
  }
}
