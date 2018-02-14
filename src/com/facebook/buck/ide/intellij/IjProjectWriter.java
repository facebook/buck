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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.ide.intellij.model.ContentRoot;
import com.facebook.buck.ide.intellij.model.IjLibrary;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.stringtemplate.v4.ST;

/** Writes the serialized representations of IntelliJ project components to disk. */
public class IjProjectWriter {

  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem projectFilesystem;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectFilesystem = projectFilesystem;
  }

  public void write(IJProjectCleaner cleaner) throws IOException {
    Path projectIdeaConfigDir = projectConfig.getProjectPaths().getIdeaConfigDir();
    projectFilesystem.mkdirs(projectIdeaConfigDir);

    writeProjectSettings(cleaner, projectConfig);

    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      ImmutableList<ContentRoot> contentRoots = projectDataPreparer.getContentRoots(module);
      Path generatedModuleFile = writeModule(module, contentRoots);
      cleaner.doNotDelete(generatedModuleFile);
    }
    for (IjLibrary library : projectDataPreparer.getLibrariesToBeWritten()) {
      Path generatedLibraryFile = writeLibrary(library);
      cleaner.doNotDelete(generatedLibraryFile);
    }
    Path indexFile = writeModulesIndex();
    cleaner.doNotDelete(indexFile);

    Path workspaceFile = writeWorkspace(projectFilesystem.resolve(projectIdeaConfigDir));
    cleaner.doNotDelete(workspaceFile);
  }

  private Path writeModule(IjModule module, ImmutableList<ContentRoot> contentRoots)
      throws IOException {
    Path path = module.getModuleImlFilePath();

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
            .map((dir) -> module.getModuleBasePath().relativize(dir))
            .orElse(null));

    StringTemplateFile.writeToFile(
        projectFilesystem,
        moduleContents,
        path,
        projectConfig.getProjectPaths().getIdeaConfigDir());
    return path;
  }

  private void writeProjectSettings(IJProjectCleaner cleaner, IjProjectConfig projectConfig)
      throws IOException {

    Optional<String> sdkName = projectConfig.getProjectJdkName();
    Optional<String> sdkType = projectConfig.getProjectJdkType();

    if (!sdkName.isPresent() || !sdkType.isPresent()) {
      return;
    }

    Path path = projectConfig.getProjectPaths().getIdeaConfigDir().resolve("misc.xml");

    ST contents = StringTemplateFile.MISC_TEMPLATE.getST();

    String languageLevelInIjFormat = getLanguageLevelFromConfig();

    contents.add("languageLevel", languageLevelInIjFormat);
    contents.add("jdk15", getJdk15FromLanguageLevel(languageLevelInIjFormat));
    contents.add("jdkName", sdkName.get());
    contents.add("jdkType", sdkType.get());
    contents.add("outputUrl", projectConfig.getOutputUrl().orElse(null));

    StringTemplateFile.writeToFile(
        projectFilesystem, contents, path, projectConfig.getProjectPaths().getIdeaConfigDir());

    cleaner.doNotDelete(path);
  }

  private String getLanguageLevelFromConfig() {
    Optional<String> languageLevelFromConfig = projectConfig.getProjectLanguageLevel();
    if (languageLevelFromConfig.isPresent()) {
      return languageLevelFromConfig.get();
    } else {
      String languageLevel =
          projectConfig.getJavaBuckConfig().getDefaultJavacOptions().getSourceLevel();
      return JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(languageLevel);
    }
  }

  private static boolean getJdk15FromLanguageLevel(String languageLevel) {
    boolean jdkUnder15 = "JDK_1_3".equals(languageLevel) || "JDK_1_4".equals(languageLevel);
    return !jdkUnder15;
  }

  private Path writeLibrary(IjLibrary library) throws IOException {
    Path path =
        projectConfig
            .getProjectPaths()
            .getLibrariesDir()
            .resolve(Util.normalizeIntelliJName(library.getName()) + ".xml");

    ST contents = StringTemplateFile.LIBRARY_TEMPLATE.getST();
    final IjProjectPaths projectPaths = projectConfig.getProjectPaths();
    contents.add("name", library.getName());
    contents.add(
        "binaryJars",
        library
            .getBinaryJars()
            .stream()
            .map(projectPaths::toProjectDirRelativeString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    contents.add(
        "classPaths",
        library
            .getClassPaths()
            .stream()
            .map(projectPaths::toProjectDirRelativeString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    contents.add(
        "sourceJars",
        library
            .getSourceJars()
            .stream()
            .map(projectPaths::toProjectDirRelativeString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    contents.add("javadocUrls", library.getJavadocUrls());
    // TODO(mkosiba): support res and assets for aar.

    StringTemplateFile.writeToFile(
        projectFilesystem, contents, path, projectConfig.getProjectPaths().getIdeaConfigDir());
    return path;
  }

  private Path writeModulesIndex() throws IOException {
    projectFilesystem.mkdirs(projectConfig.getProjectPaths().getIdeaConfigDir());
    Path path = projectConfig.getProjectPaths().getIdeaConfigDir().resolve("modules.xml");

    ST moduleIndexContents = StringTemplateFile.MODULE_INDEX_TEMPLATE.getST();
    moduleIndexContents.add("modules", projectDataPreparer.getModuleIndexEntries());

    StringTemplateFile.writeToFile(
        projectFilesystem,
        moduleIndexContents,
        path,
        projectConfig.getProjectPaths().getIdeaConfigDir());
    return path;
  }

  private Path writeWorkspace(Path projectIdeaConfigDir) throws IOException {
    WorkspaceUpdater workspaceUpdater = new WorkspaceUpdater(projectIdeaConfigDir);
    workspaceUpdater.updateOrCreateWorkspace();

    return Paths.get(workspaceUpdater.getWorkspaceFile().toString());
  }
}
