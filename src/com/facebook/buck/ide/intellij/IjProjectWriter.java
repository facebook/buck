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

import com.facebook.buck.ide.intellij.model.IjLibrary;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.MoreCollectors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.stringtemplate.v4.ST;

/** Writes the serialized representations of IntelliJ project components to disk. */
public class IjProjectWriter {

  private IjProjectTemplateDataPreparer projectDataPreparer;
  private IjProjectConfig projectConfig;
  private ProjectFilesystem projectFilesystem;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectFilesystem = projectFilesystem;
  }

  public void write(IJProjectCleaner cleaner) throws IOException {
    projectFilesystem.mkdirs(IjProjectPaths.IDEA_CONFIG_DIR);

    writeProjectSettings(cleaner, projectConfig);

    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      Path generatedModuleFile = writeModule(module);
      cleaner.doNotDelete(generatedModuleFile);
    }
    for (IjLibrary library : projectDataPreparer.getLibrariesToBeWritten()) {
      Path generatedLibraryFile = writeLibrary(library);
      cleaner.doNotDelete(generatedLibraryFile);
    }
    Path indexFile = writeModulesIndex();
    cleaner.doNotDelete(indexFile);
  }

  private Path writeModule(IjModule module) throws IOException {
    Path path = module.getModuleImlFilePath();

    ST moduleContents = StringTemplateFile.MODULE_TEMPLATE.getST();

    moduleContents.add("contentRoot", projectDataPreparer.getContentRoot(module));
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

    StringTemplateFile.writeToFile(projectFilesystem, moduleContents, path);
    return path;
  }

  private void writeProjectSettings(IJProjectCleaner cleaner, IjProjectConfig projectConfig)
      throws IOException {

    Optional<String> sdkName = projectConfig.getProjectJdkName();
    Optional<String> sdkType = projectConfig.getProjectJdkType();

    if (!sdkName.isPresent() || !sdkType.isPresent()) {
      return;
    }

    Path path = IjProjectPaths.IDEA_CONFIG_DIR.resolve("misc.xml");

    ST contents = StringTemplateFile.MISC_TEMPLATE.getST();

    String languageLevelInIjFormat = getLanguageLevelFromConfig();

    contents.add("languageLevel", languageLevelInIjFormat);
    contents.add("jdk15", getJdk15FromLanguageLevel(languageLevelInIjFormat));
    contents.add("jdkName", sdkName.get());
    contents.add("jdkType", sdkType.get());

    StringTemplateFile.writeToFile(projectFilesystem, contents, path);

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
    Path path = IjProjectPaths.LIBRARIES_DIR.resolve(library.getName() + ".xml");

    ST contents = StringTemplateFile.LIBRARY_TEMPLATE.getST();
    contents.add("name", library.getName());
    contents.add(
        "binaryJars",
        library
            .getBinaryJars()
            .stream()
            .map(MorePaths::pathWithUnixSeparators)
            .collect(MoreCollectors.toImmutableSortedSet()));
    contents.add(
        "classPaths",
        library
            .getClassPaths()
            .stream()
            .map(MorePaths::pathWithUnixSeparators)
            .collect(MoreCollectors.toImmutableSortedSet()));
    contents.add(
        "sourceJars",
        library
            .getSourceJars()
            .stream()
            .map(MorePaths::pathWithUnixSeparators)
            .collect(MoreCollectors.toImmutableSortedSet()));
    contents.add("javadocUrls", library.getJavadocUrls());
    //TODO(mkosiba): support res and assets for aar.

    StringTemplateFile.writeToFile(projectFilesystem, contents, path);
    return path;
  }

  private Path writeModulesIndex() throws IOException {
    projectFilesystem.mkdirs(IjProjectPaths.IDEA_CONFIG_DIR);
    Path path = IjProjectPaths.IDEA_CONFIG_DIR.resolve("modules.xml");

    ST moduleIndexContents = StringTemplateFile.MODULE_INDEX_TEMPLATE.getST();
    moduleIndexContents.add("modules", projectDataPreparer.getModuleIndexEntries());

    StringTemplateFile.writeToFile(projectFilesystem, moduleIndexContents, path);
    return path;
  }
}
