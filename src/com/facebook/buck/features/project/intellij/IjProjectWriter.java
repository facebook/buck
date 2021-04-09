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

import static com.facebook.buck.features.project.intellij.IjProjectPaths.getUrl;

import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

/** Writes the serialized representations of IntelliJ project components to disk. */
public class IjProjectWriter {

  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem projectFilesystem;
  private final IntellijModulesListParser modulesParser;
  private final IJProjectCleaner cleaner;
  private final ProjectFilesystem outFilesystem;
  private final IjProjectPaths projectPaths;
  private final BuckOutPathConverter buckOutPathConverter;
  private final ModuleInfoManager moduleInfoManager;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem,
      IntellijModulesListParser modulesParser,
      IJProjectCleaner cleaner,
      ProjectFilesystem outFilesystem,
      BuckOutPathConverter buckOutPathConverter) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectPaths = projectConfig.getProjectPaths();
    this.projectFilesystem = projectFilesystem;
    this.modulesParser = modulesParser;
    this.cleaner = cleaner;
    this.outFilesystem = outFilesystem;
    this.buckOutPathConverter = buckOutPathConverter;
    this.moduleInfoManager = new ModuleInfoManager(projectConfig);
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
                writeModule(module);
              } catch (IOException exception) {
                throw new RuntimeException(exception);
              }
            });
    projectDataPreparer
        .getProjectLibrariesToBeWritten()
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
    moduleInfoManager.write();
  }

  private boolean writeModule(IjModule module) throws IOException {

    ST moduleContents = StringTemplateFile.MODULE_TEMPLATE.getST();
    ImmutableList<ContentRoot> contentRoots = projectDataPreparer.getContentRoots(module);
    ImmutableSet<IjSourceFolder> generatedFolders =
        projectDataPreparer.getGeneratedSourceFolders(module);
    Map<String, Object> androidProperties = projectDataPreparer.getAndroidProperties(module);

    if (buckOutPathConverter.hasBuckOutPathForGeneratedProjectFiles()) {
      contentRoots =
          contentRoots.stream()
              .map(buckOutPathConverter::convert)
              .collect(ImmutableList.toImmutableList());
      generatedFolders =
          generatedFolders.stream()
              .map(buckOutPathConverter::convert)
              .collect(ImmutableSet.toImmutableSet());
      androidProperties = buckOutPathConverter.convert(androidProperties);
    }

    moduleContents.add("contentRoots", contentRoots);
    ImmutableSet<IjDependencyListBuilder.DependencyEntry> dependencies =
        projectDataPreparer.getDependencies(
            module,
            projectConfig.isModuleLibraryEnabled()
                ? library -> this.prepareLibraryToBeWritten(library, module)
                : null);
    moduleContents.add("dependencies", dependencies);
    moduleContents.add("generatedSourceFolders", generatedFolders);
    moduleContents.add("androidFacet", androidProperties);
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

    moduleInfoManager.addModuleInfo(
        module.getName(), projectPaths.getModuleDir(module).toString(), dependencies, contentRoots);
    return writeTemplate(moduleContents, projectPaths.getModuleImlFilePath(module, projectConfig));
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

  private IjLibrary prepareLibraryToBeWritten(IjLibrary library, @Nullable IjModule module) {
    Preconditions.checkState(library.getLevel() != IjLibrary.Level.MODULE || module != null);
    Function<Path, Path> pathTransformer;
    if (module == null) {
      pathTransformer = projectPaths::getProjectRelativePath;
    } else {
      pathTransformer = path -> projectPaths.getModuleRelativePath(path, module);
    }
    if (buckOutPathConverter.hasBuckOutPathForGeneratedProjectFiles()) {
      return library.copyWithTransformer(
          path -> pathTransformer.apply(buckOutPathConverter.convert(path)), null);
    } else {
      return library.copyWithTransformer(pathTransformer, null);
    }
  }

  private void writeLibrary(IjLibrary library) throws IOException {
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
    contents.add("binaryJars", library.getBinaryJars());
    contents.add("classPaths", library.getClassPaths());
    contents.add("sourceJars", library.getSourceJars());
    contents.add("javadocUrls", library.getJavadocUrls());
    // TODO(mkosiba): support res and assets for aar.

    Path path = projectPaths.getLibraryXmlFilePath(library, projectConfig);
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

    projectDataPreparer
        .getModulesToBeWritten()
        .parallelStream()
        .forEach(
            module -> {
              try {
                writeModule(module);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    projectDataPreparer
        .getProjectLibrariesToBeWritten()
        .parallelStream()
        .forEach(
            library -> {
              try {
                writeLibrary(library);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    updateModulesIndex(projectDataPreparer.getModulesToBeWritten());
    moduleInfoManager.update();
  }

  /** Update the modules.xml file with any new modules from the given set */
  private void updateModulesIndex(ImmutableSet<IjModule> modulesEdited) throws IOException {
    final Set<ModuleIndexEntry> existingModules =
        modulesParser.getAllModules(
            projectFilesystem.newFileInputStream(getIdeaConfigDir().resolve("modules.xml")));
    final Set<Path> existingModuleFilepaths =
        existingModules.stream()
            .map(ModuleIndexEntry::getFilePath)
            .map(PathFormatter::pathWithUnixSeparators)
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Path> remainingModuleFilepaths =
        modulesEdited.stream()
            .map(module -> projectPaths.getModuleImlFilePath(module, projectConfig))
            .map(PathFormatter::pathWithUnixSeparators)
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
                ModuleIndexEntry.of(
                    getUrl(projectPaths.getProjectQualifiedPath(modulePath)),
                    projectPaths.getProjectRelativePath(modulePath),
                    projectConfig.getModuleGroupName())));

    // Write out the merged set to disk
    writeModulesIndex(finalModulesBuilder.build());
  }
}
