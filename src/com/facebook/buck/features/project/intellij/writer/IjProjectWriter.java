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

import static com.facebook.buck.features.project.intellij.IjProjectPaths.getUrl;

import com.facebook.buck.features.project.intellij.BuckOutPathConverter;
import com.facebook.buck.features.project.intellij.IJProjectCleaner;
import com.facebook.buck.features.project.intellij.IjDependencyListBuilder;
import com.facebook.buck.features.project.intellij.IjLibraryNameConflictResolver;
import com.facebook.buck.features.project.intellij.IjProjectPaths;
import com.facebook.buck.features.project.intellij.IjProjectTemplateDataPreparer;
import com.facebook.buck.features.project.intellij.IntellijModulesListParser;
import com.facebook.buck.features.project.intellij.JavaLanguageLevelHelper;
import com.facebook.buck.features.project.intellij.ModuleInfoManager;
import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Writes the serialized representations of IntelliJ project components to disk. */
public abstract class IjProjectWriter {
  protected final IjProjectTemplateDataPreparer projectDataPreparer;
  protected final IjProjectConfig projectConfig;
  protected final ProjectFilesystem projectFilesystem;
  protected final IntellijModulesListParser modulesParser;
  protected final IJProjectCleaner cleaner;
  protected final ProjectFilesystem outFilesystem;
  protected final IjProjectPaths projectPaths;
  protected final BuckOutPathConverter buckOutPathConverter;
  protected final ModuleInfoManager moduleInfoManager;
  protected final IjLibraryNameConflictResolver nameConflictResolver;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem,
      IntellijModulesListParser modulesParser,
      IJProjectCleaner cleaner,
      ProjectFilesystem outFilesystem,
      BuckOutPathConverter buckOutPathConverter,
      IjLibraryNameConflictResolver nameConflictResolver) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectPaths = projectConfig.getProjectPaths();
    this.projectFilesystem = projectFilesystem;
    this.modulesParser = modulesParser;
    this.cleaner = cleaner;
    this.outFilesystem = outFilesystem;
    this.buckOutPathConverter = buckOutPathConverter;
    this.moduleInfoManager = new ModuleInfoManager(projectConfig, cleaner);
    this.nameConflictResolver = nameConflictResolver;
  }

  /** Write entire project to disk */
  public void write() throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());
    writeProjectSettings();
    writeModulesAndLibraries();
    writeModulesIndex(projectDataPreparer.getModuleIndexEntries());
    writeWorkspace();
    moduleInfoManager.write();
  }

  protected abstract void writeWorkspace() throws IOException;

  /**
   * Update project files and modules index
   *
   * @throws IOException if a file cannot be written
   */
  public void update() throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());

    // In this method, we make use of the IjLibraryNameConflictResolver to resolve conflicts in
    // project library names just similar to the `write` workflow. Here we make assumption that
    // there are no existing conflicts in the current library names.
    writeModulesAndLibraries();

    updateModulesIndex(projectDataPreparer.getModulesToBeWritten());
    moduleInfoManager.update();
  }

  protected abstract void writeModulesIndex(ImmutableSortedSet<ModuleIndexEntry> moduleIndexEntries)
      throws IOException;

  protected abstract void writeProjectSettings() throws IOException;

  protected abstract boolean writeModule(IjModule module) throws IOException;

  protected abstract void writeLibrary(IjLibrary library) throws IOException;

  /** Get language level in Intellij format */
  protected String getLanguageLevelFromConfig() {
    Optional<String> languageLevelFromConfig = projectConfig.getProjectLanguageLevel();
    if (languageLevelFromConfig.isPresent()) {
      return languageLevelFromConfig.get();
    } else {
      String languageLevel =
          projectConfig
              .getJavaBuckConfig()
              .getJavacLanguageLevelOptions()
              .getSourceLevelValue()
              .getVersion();
      return JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(languageLevel);
    }
  }

  protected String getLanguageLevel(IjModule module) {
    return JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(
        module.getLanguageLevel().orElse(null));
  }

  /** Returns the path of META-INF directory */
  protected String getMetaInfDirectory(IjModule module) {
    return module
        .getMetaInfDirectory()
        .map((dir) -> getUrl(projectPaths.getModuleQualifiedPath(dir, module)))
        .orElse(null);
  }

  protected static boolean getJdk15FromLanguageLevel(String languageLevel) {
    boolean jdkUnder15 = "JDK_1_3".equals(languageLevel) || "JDK_1_4".equals(languageLevel);
    return !jdkUnder15;
  }

  /** Return the path of .idea folder */
  protected Path getIdeaConfigDir() {
    return projectPaths.getIdeaConfigDir();
  }

  /** Clone the library with buck-out path transformer. */
  protected IjLibrary prepareLibraryToBeWritten(IjLibrary library, @Nullable IjModule module) {
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

  /** Extract the data from IjModule that is required to build the module file. */
  protected ModuleData prepareModuleToBeWritten(IjModule module) throws IOException {
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

    ImmutableSet<IjDependencyListBuilder.DependencyEntry> dependencies =
        projectDataPreparer.getDependencies(
            module,
            projectConfig.isModuleLibraryEnabled()
                ? library -> this.prepareLibraryToBeWritten(library, module)
                : null);

    ModuleData moduleData =
        ModuleData.of(generatedFolders, androidProperties, dependencies, contentRoots);

    return moduleData;
  }

  /** Writes the generated project file to filesystem, if exists then skip writing. */
  public static boolean writeToFile(
      ProjectFilesystem projectFilesystem,
      byte[] renderedContentsBytes,
      Path path,
      Path ideaConfigDir)
      throws IOException {

    if (projectFilesystem.exists(path)) {
      Sha1HashCode fileSha1 = projectFilesystem.computeSha1(path);
      Sha1HashCode contentsSha1 =
          Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(renderedContentsBytes));
      if (fileSha1.equals(contentsSha1)) {
        return false;
      }

      boolean danglingTempFile = false;
      Path tempFile =
          projectFilesystem.createTempFile(ideaConfigDir, path.getFileName().toString(), ".tmp");
      try {
        danglingTempFile = true;
        projectFilesystem.writeBytesToPath(renderedContentsBytes, tempFile);
        projectFilesystem.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        danglingTempFile = false;
      } finally {
        if (danglingTempFile) {
          projectFilesystem.deleteFileAtPath(tempFile);
        }
      }
    } else {
      projectFilesystem.createParentDirs(path);
      projectFilesystem.writeBytesToPath(renderedContentsBytes, path);
    }
    return true;
  }

  /** Update the modules.xml file with any new modules from the given set */
  protected void updateModulesIndex(ImmutableSet<IjModule> modulesEdited) throws IOException {
    String modulesFileName =
        projectConfig.isGenerateProjectFilesAsJsonEnabled() ? "modules.json" : "modules.xml";
    String extension = projectConfig.isGenerateProjectFilesAsJsonEnabled() ? ".json" : ".iml";

    final Set<ModuleIndexEntry> existingModules =
        modulesParser.getAllModules(
            projectFilesystem.newFileInputStream(getIdeaConfigDir().resolve(modulesFileName)));
    final Set<Path> existingModuleFilepaths =
        existingModules.stream()
            .map(ModuleIndexEntry::getFilePath)
            .map(PathFormatter::pathWithUnixSeparators)
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Path> remainingModuleFilepaths =
        modulesEdited.stream()
            .map(module -> projectPaths.getModuleFilePath(module, projectConfig, extension))
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

  /** Write the module and library project-files. */
  protected void writeModulesAndLibraries() {

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
  }
}
