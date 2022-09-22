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
import com.facebook.buck.features.project.intellij.WorkspaceUpdater;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.stringtemplate.v4.ST;

/** Writes the serialized representations of IntelliJ project components to disk in XML format. */
public class IjProjectXmlWriter extends IjProjectWriter {

  public IjProjectXmlWriter(
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

  /** Write entire project to disk */
  @Override
  public void write() throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());

    writeProjectSettings();
    writeModulesAndLibraries();
    writeModulesIndex(projectDataPreparer.getModuleIndexEntries());
    writeWorkspace();
    moduleInfoManager.write();
  }

  @Override
  protected boolean writeModule(IjModule module) throws IOException {

    ST moduleContents = StringTemplateFile.MODULE_TEMPLATE.getST();

    ModuleData moduleData = prepareModuleToBeWritten(module);

    moduleContents.add("contentRoots", moduleData.getContentRoots());
    moduleContents.add("dependencies", moduleData.getDependencies());
    moduleContents.add("generatedSourceFolders", moduleData.getGeneratedFolders());
    moduleContents.add("androidFacet", moduleData.getAndroidProperties());
    moduleContents.add("sdk", module.getModuleType().getSdkName(projectConfig).orElse(null));
    moduleContents.add("sdkType", module.getModuleType().getSdkType(projectConfig));
    moduleContents.add("languageLevel", getLanguageLevel(module));
    moduleContents.add("moduleType", module.getModuleType().getImlModuleType());
    moduleContents.add("metaInfDirectory", getMetaInfDirectory(module));

    moduleInfoManager.addModuleInfo(
        module.getName(),
        projectPaths.getModuleDir(module).toString(),
        moduleData.getDependencies(),
        moduleData.getGeneratedFolders(),
        moduleData.getContentRoots());

    return writeTemplate(
        moduleContents, projectPaths.getModuleFilePath(module, projectConfig, ".iml"));
  }

  @Override
  protected void writeProjectSettings() throws IOException {

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

  @Override
  protected void writeLibrary(IjLibrary library) throws IOException {
    ST contents = null;
    if (library.getType() == IjLibrary.Type.DEFAULT) {
      contents = StringTemplateFile.LIBRARY_TEMPLATE.getST();
    } else if (library.getType() == IjLibrary.Type.KOTLIN_JAVA_RUNTIME) {
      Optional<Path> templatePath = projectConfig.getKotlinJavaRuntimeLibraryTemplatePath();
      if (templatePath.isPresent()) {
        contents = StringTemplateFile.getST(templatePath.get());
      }
    }
    if (contents == null) {
      return;
    }

    library = prepareLibraryToBeWritten(library, null);

    contents.add("name", library.getName());
    contents.add("annotationJars", library.getAnnotationJars());
    contents.add("binaryJars", library.getBinaryJars());
    contents.add("classPaths", library.getClassPaths());
    contents.add("sourceJars", library.getSourceJars());
    contents.add("javadocUrls", library.getJavadocUrls());
    // TODO(mkosiba): support res and assets for aar.

    Path path =
        projectPaths.getLibraryXmlFilePath(library, projectConfig, nameConflictResolver, ".xml");
    writeTemplate(contents, path);
  }

  // Write modules.xml
  @Override
  protected void writeModulesIndex(ImmutableSortedSet<ModuleIndexEntry> moduleEntries)
      throws IOException {
    ST moduleIndexContents = StringTemplateFile.MODULE_INDEX_TEMPLATE.getST();
    moduleIndexContents.add("modules", moduleEntries);

    writeTemplate(moduleIndexContents, getIdeaConfigDir().resolve("modules.xml"));
  }

  @Override
  protected void writeWorkspace() throws IOException {
    WorkspaceUpdater workspaceUpdater = new WorkspaceUpdater(outFilesystem, getIdeaConfigDir());
    workspaceUpdater.updateOrCreateWorkspace();
    cleaner.doNotDelete(workspaceUpdater.getWorkspacePath());
  }

  /**
   * Writes template to output project filesystem
   *
   * @param path Relative path from project root
   */
  private boolean writeTemplate(ST contents, Path path) throws IOException {
    byte[] renderedContentsBytes = contents.render().getBytes();
    boolean didUpdate = writeToFile(outFilesystem, renderedContentsBytes, path, getIdeaConfigDir());
    cleaner.doNotDelete(path);
    return didUpdate;
  }
}
