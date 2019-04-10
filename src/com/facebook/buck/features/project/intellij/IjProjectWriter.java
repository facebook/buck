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

package com.facebook.buck.features.project.intellij;

import static com.facebook.buck.features.project.intellij.IjProjectPaths.getUrl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.stringtemplate.v4.ST;

/** Writes the serialized representations of IntelliJ project components to disk. */
public class IjProjectWriter {
  static final String TARGET_INFO_MAP_FILENAME = "target-info.json";
  static final String INTELLIJ_TYPE = "intellij.type";
  static final String INTELLIJ_NAME = "intellij.name";
  static final String INTELLIJ_FILE_PATH = "intellij.file_path";
  static final String MODULE_TYPE = "module";
  static final String LIBRARY_TYPE = "library";

  private final TargetGraph targetGraph;
  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem projectFilesystem;
  private final IntellijModulesListParser modulesParser;
  private final IJProjectCleaner cleaner;
  private final ProjectFilesystem outFilesystem;
  private final IjProjectPaths projectPaths;

  public IjProjectWriter(
      TargetGraph targetGraph,
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem,
      IntellijModulesListParser modulesParser,
      IJProjectCleaner cleaner,
      ProjectFilesystem outFilesystem) {
    this.targetGraph = targetGraph;
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectPaths = projectConfig.getProjectPaths();
    this.projectFilesystem = projectFilesystem;
    this.modulesParser = modulesParser;
    this.cleaner = cleaner;
    this.outFilesystem = outFilesystem;
  }

  /** Write entire project to disk */
  public void write() throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());

    writeProjectSettings();

    projectDataPreparer
        .getModulesToBeWritten()
        .parallelStream()
        .forEach(
            module -> {
              try {
                writeModule(module, projectDataPreparer.getContentRoots(module));
              } catch (IOException exception) {
                throw new RuntimeException(exception);
              }
            });
    projectDataPreparer
        .getLibrariesToBeWritten()
        .parallelStream()
        .forEach(
            library -> {
              try {
                writeLibrary(library);
              } catch (IOException exception) {
                throw new RuntimeException(exception);
              }
            });

    writeModulesIndex(projectDataPreparer.getModuleIndexEntries());
    writeWorkspace();

    if (projectConfig.isGeneratingTargetInfoMapEnabled()) {
      writeTargetInfoMap(projectDataPreparer, false);
    }
  }

  private Map<String, Map<String, String>> readTargetInfoMap() throws IOException {
    Path targetInfoMapPath = getTargetInfoMapPath();
    return outFilesystem.exists(targetInfoMapPath)
        ? ObjectMappers.createParser(outFilesystem.newFileInputStream(targetInfoMapPath))
            .readValueAs(new TypeReference<TreeMap<String, TreeMap<String, String>>>() {})
        : Maps.newTreeMap();
  }

  private void writeTargetInfoMap(IjProjectTemplateDataPreparer projectDataPreparer, boolean update)
      throws IOException {
    Map<String, Map<String, String>> targetInfoMap =
        update ? readTargetInfoMap() : Maps.newTreeMap();
    projectDataPreparer
        .getModulesToBeWritten()
        .forEach(
            module -> {
              module
                  .getTargets()
                  .forEach(
                      target -> {
                        Map<String, String> targetInfo = Maps.newTreeMap();
                        targetInfo.put(INTELLIJ_TYPE, MODULE_TYPE);
                        targetInfo.put(INTELLIJ_NAME, module.getName());
                        targetInfo.put(
                            INTELLIJ_FILE_PATH,
                            projectPaths.getModuleImlFilePath(module).toString());
                        targetInfo.put("buck.type", getRuleNameForBuildTarget(target));
                        targetInfoMap.put(target.getFullyQualifiedName(), targetInfo);
                      });
            });
    projectDataPreparer
        .getLibrariesToBeWritten()
        .forEach(
            library -> {
              library
                  .getTargets()
                  .forEach(
                      target -> {
                        Map<String, String> targetInfo = Maps.newTreeMap();
                        targetInfo.put(INTELLIJ_TYPE, LIBRARY_TYPE);
                        targetInfo.put(INTELLIJ_NAME, library.getName());
                        targetInfo.put(
                            INTELLIJ_FILE_PATH,
                            projectPaths.getLibraryXmlFilePath(library).toString());
                        targetInfo.put("buck.type", getRuleNameForBuildTarget(target));
                        targetInfoMap.put(target.getFullyQualifiedName(), targetInfo);
                      });
            });
    Path targetInfoMapPath = getTargetInfoMapPath();
    try (JsonGenerator generator =
        ObjectMappers.createGenerator(outFilesystem.newFileOutputStream(targetInfoMapPath))
            .useDefaultPrettyPrinter()) {
      generator.writeObject(targetInfoMap);
    }
  }

  private Path getTargetInfoMapPath() {
    return getIdeaConfigDir().resolve(TARGET_INFO_MAP_FILENAME);
  }

  private String getRuleNameForBuildTarget(BuildTarget buildTarget) {
    return targetGraph.get(buildTarget).getRuleType().getName();
  }

  private boolean writeModule(IjModule module, ImmutableList<ContentRoot> contentRoots)
      throws IOException {

    ST moduleContents = StringTemplateFile.MODULE_TEMPLATE.getST();

    moduleContents.add("contentRoots", contentRoots);
    moduleContents.add("dependencies", projectDataPreparer.getDependencies(module));
    moduleContents.add(
        "generatedSourceFolders", projectDataPreparer.getGeneratedSourceFolders(module));
    moduleContents.add("androidFacet", projectDataPreparer.getAndroidProperties(module));
    moduleContents.add("sdk", module.getModuleType().getSdkName(projectConfig).orElse(null));
    moduleContents.add("sdkType", module.getModuleType().getSdkType(projectConfig));
    moduleContents.add(
        "languageLevel",
        JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(
            module.getLanguageLevel().orElse(null)));
    moduleContents.add("moduleType", module.getModuleType().getImlModuleType());
    moduleContents.add(
        "metaInfDirectory",
        module
            .getMetaInfDirectory()
            .map((dir) -> getUrl(projectPaths.getModuleQualifiedPath(dir, module)))
            .orElse(null));

    return writeTemplate(moduleContents, projectPaths.getModuleImlFilePath(module));
  }

  private void writeProjectSettings() throws IOException {

    Optional<String> sdkName = projectConfig.getProjectJdkName();
    Optional<String> sdkType = projectConfig.getProjectJdkType();

    if (!sdkName.isPresent() || !sdkType.isPresent()) {
      return;
    }

    ST contents = StringTemplateFile.MISC_TEMPLATE.getST();

    String languageLevelInIjFormat = getLanguageLevelFromConfig();

    contents.add("languageLevel", languageLevelInIjFormat);
    contents.add("jdk15", getJdk15FromLanguageLevel(languageLevelInIjFormat));
    contents.add("jdkName", sdkName.get());
    contents.add("jdkType", sdkType.get());
    contents.add("outputUrl", projectConfig.getOutputUrl().orElse(null));

    writeTemplate(contents, getIdeaConfigDir().resolve("misc.xml"));
  }

  private String getLanguageLevelFromConfig() {
    Optional<String> languageLevelFromConfig = projectConfig.getProjectLanguageLevel();
    if (languageLevelFromConfig.isPresent()) {
      return languageLevelFromConfig.get();
    } else {
      String languageLevel =
          projectConfig.getJavaBuckConfig().getJavacLanguageLevelOptions().getSourceLevel();
      return JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(languageLevel);
    }
  }

  private static boolean getJdk15FromLanguageLevel(String languageLevel) {
    boolean jdkUnder15 = "JDK_1_3".equals(languageLevel) || "JDK_1_4".equals(languageLevel);
    return !jdkUnder15;
  }

  private void writeLibrary(IjLibrary library) throws IOException {
    ST contents = StringTemplateFile.LIBRARY_TEMPLATE.getST();
    contents.add("name", library.getName());
    contents.add(
        "binaryJars",
        library.getBinaryJars().stream()
            .map(projectPaths::getProjectRelativePath)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    contents.add(
        "classPaths",
        library.getClassPaths().stream()
            .map(projectPaths::getProjectRelativePath)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    contents.add(
        "sourceJars",
        library.getSourceJars().stream()
            .map(projectPaths::getProjectRelativePath)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    contents.add("javadocUrls", library.getJavadocUrls());
    // TODO(mkosiba): support res and assets for aar.

    Path path = projectPaths.getLibraryXmlFilePath(library);
    writeTemplate(contents, path);
  }

  // Write modules.xml
  private void writeModulesIndex(ImmutableSortedSet<ModuleIndexEntry> moduleEntries)
      throws IOException {
    ST moduleIndexContents = StringTemplateFile.MODULE_INDEX_TEMPLATE.getST();
    moduleIndexContents.add("modules", moduleEntries);

    writeTemplate(moduleIndexContents, getIdeaConfigDir().resolve("modules.xml"));
  }

  private Path getIdeaConfigDir() {
    return projectPaths.getIdeaConfigDir();
  }

  /**
   * Writes template to output project filesystem
   *
   * @param path Relative path from project root
   */
  private boolean writeTemplate(ST contents, Path path) throws IOException {
    boolean didUpdate =
        StringTemplateFile.writeToFile(outFilesystem, contents, path, getIdeaConfigDir());
    cleaner.doNotDelete(path);
    return didUpdate;
  }

  private void writeWorkspace() throws IOException {
    WorkspaceUpdater workspaceUpdater = new WorkspaceUpdater(outFilesystem, getIdeaConfigDir());
    workspaceUpdater.updateOrCreateWorkspace();
    cleaner.doNotDelete(workspaceUpdater.getWorkspacePath());
  }

  /**
   * Update project files and modules index
   *
   * @throws IOException if a file cannot be written
   */
  public void update() throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());
    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      ImmutableList<ContentRoot> contentRoots = projectDataPreparer.getContentRoots(module);
      writeModule(module, contentRoots);
    }
    for (IjLibrary library : projectDataPreparer.getLibrariesToBeWritten()) {
      writeLibrary(library);
    }
    updateModulesIndex(projectDataPreparer.getModulesToBeWritten());

    if (projectConfig.isGeneratingTargetInfoMapEnabled()) {
      writeTargetInfoMap(projectDataPreparer, true);
    }
  }

  /** Update the modules.xml file with any new modules from the given set */
  private void updateModulesIndex(ImmutableSet<IjModule> modulesEdited) throws IOException {
    final Set<ModuleIndexEntry> existingModules =
        modulesParser.getAllModules(
            projectFilesystem.newFileInputStream(getIdeaConfigDir().resolve("modules.xml")));
    final Set<Path> existingModuleFilepaths =
        existingModules.stream()
            .map(ModuleIndexEntry::getFilePath)
            .map(MorePaths::pathWithUnixSeparators)
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Path> remainingModuleFilepaths =
        modulesEdited.stream()
            .map(projectPaths::getModuleImlFilePath)
            .map(MorePaths::pathWithUnixSeparators)
            .map(Paths::get)
            .filter(modulePath -> !existingModuleFilepaths.contains(modulePath))
            .collect(ImmutableSet.toImmutableSet());

    // Merge the existing and new modules into a single sorted set
    ImmutableSortedSet.Builder<ModuleIndexEntry> finalModulesBuilder =
        ImmutableSortedSet.orderedBy(Comparator.<ModuleIndexEntry>naturalOrder());
    // Add the existing definitions
    finalModulesBuilder.addAll(existingModules);
    // Add any new module definitions that we haven't seen yet
    remainingModuleFilepaths.forEach(
        modulePath ->
            finalModulesBuilder.add(
                ModuleIndexEntry.builder()
                    .setFilePath(projectPaths.getProjectRelativePath(modulePath))
                    .setFileUrl(getUrl(projectPaths.getProjectQualifiedPath(modulePath)))
                    .setGroup(projectConfig.getModuleGroupName())
                    .build()));

    // Write out the merged set to disk
    writeModulesIndex(finalModulesBuilder.build());
  }
}
