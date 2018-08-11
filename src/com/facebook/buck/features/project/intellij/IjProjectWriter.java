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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.IjProjectElement;
import com.facebook.buck.features.project.intellij.model.ModuleIndexEntry;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import org.stringtemplate.v4.ST;

/** Writes the serialized representations of IntelliJ project components to disk. */
public class IjProjectWriter {
  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem projectFilesystem;
  private final IntellijModulesListParser modulesParser;
  private final IJProjectCleaner cleaner;
  private final ProjectFilesystem outFilesystem;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem,
      IntellijModulesListParser modulesParser,
      IJProjectCleaner cleaner,
      ProjectFilesystem outFilesystem) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectFilesystem = projectFilesystem;
    this.modulesParser = modulesParser;
    this.cleaner = cleaner;
    this.outFilesystem = outFilesystem;
  }

  /** Write entire project to disk */
  public void write() throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());

    writeProjectSettings();

    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      ImmutableList<ContentRoot> contentRoots = projectDataPreparer.getContentRoots(module);
      writeModule(module, contentRoots);
    }
    for (IjLibrary library : projectDataPreparer.getLibrariesToBeWritten()) {
      writeLibrary(library);
    }
    writeModulesIndex(projectDataPreparer.getModuleIndexEntries());
    writeWorkspace();
  }

  private void writeModule(IjModule module, ImmutableList<ContentRoot> contentRoots)
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
            .map((dir) -> module.getModuleBasePath().relativize(dir))
            .orElse(null));

    writeTemplate(moduleContents, module.getModuleImlFilePath());
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
          projectConfig.getJavaBuckConfig().getDefaultJavacOptions().getSourceLevel();
      return JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(languageLevel);
    }
  }

  private static boolean getJdk15FromLanguageLevel(String languageLevel) {
    boolean jdkUnder15 = "JDK_1_3".equals(languageLevel) || "JDK_1_4".equals(languageLevel);
    return !jdkUnder15;
  }

  private void writeLibrary(IjLibrary library) throws IOException {
    ST contents = StringTemplateFile.LIBRARY_TEMPLATE.getST();
    IjProjectPaths projectPaths = projectConfig.getProjectPaths();
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

    Path path =
        projectConfig
            .getProjectPaths()
            .getLibrariesDir()
            .resolve(Util.normalizeIntelliJName(library.getName()) + ".xml");
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
    return projectConfig.getProjectPaths().getIdeaConfigDir();
  }

  /**
   * Writes template to output project filesystem
   *
   * @param path Relative path from project root
   */
  private void writeTemplate(ST contents, Path path) throws IOException {
    StringTemplateFile.writeToFile(outFilesystem, contents, path, getIdeaConfigDir());
    cleaner.doNotDelete(path);
  }

  private void writeWorkspace() throws IOException {
    WorkspaceUpdater workspaceUpdater = new WorkspaceUpdater(outFilesystem, getIdeaConfigDir());
    workspaceUpdater.updateOrCreateWorkspace();
    cleaner.doNotDelete(workspaceUpdater.getWorkspacePath());
  }

  /**
   * Update just the roots that were passed in
   *
   * @param targetGraphAndTargets
   * @param moduleGraph
   * @throws IOException
   */
  public void update(TargetGraphAndTargets targetGraphAndTargets, IjModuleGraph moduleGraph)
      throws IOException {
    outFilesystem.mkdirs(getIdeaConfigDir());
    Set<BuildTarget> modulesToUpdate =
        targetGraphAndTargets
            .getProjectRoots()
            .stream()
            .map(TargetNode::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    // Find all modules that contain one or more of our targets
    final ImmutableSet<IjModule> modulesEdited =
        projectDataPreparer
            .getModulesToBeWritten()
            .stream()
            .filter(module -> !Sets.intersection(module.getTargets(), modulesToUpdate).isEmpty())
            .collect(ImmutableSet.toImmutableSet());
    // Find all direct dependencies of our modules
    final ImmutableSet<IjProjectElement> depsToKeep =
        modulesEdited
            .stream()
            .flatMap(module -> moduleGraph.getDepsFor(module).keySet().stream())
            .collect(ImmutableSet.toImmutableSet());
    // Find all libraries which are direct deps of the modules we found above
    final ImmutableSet<IjLibrary> librariesNeeded =
        projectDataPreparer
            .getLibrariesToBeWritten()
            .stream()
            .filter(depsToKeep::contains)
            .collect(ImmutableSet.toImmutableSet());

    // Write out the modules that contain our targets
    for (IjModule module : modulesEdited) {
      ImmutableList<ContentRoot> contentRoots = projectDataPreparer.getContentRoots(module);
      writeModule(module, contentRoots);
    }
    // Write out the libraries that our modules depend on
    for (IjLibrary library : librariesNeeded) {
      writeLibrary(library);
    }
    updateModulesIndex(modulesEdited);
  }

  /** Update the modules.xml file with any new modules from the given set */
  private void updateModulesIndex(ImmutableSet<IjModule> modulesEdited) throws IOException {
    Path path = projectFilesystem.resolve(getIdeaConfigDir().resolve("modules.xml"));
    final Set<ModuleIndexEntry> existingModules = modulesParser.getAllModules(path);
    final Set<Path> existingModuleFilepaths =
        existingModules
            .stream()
            .map(ModuleIndexEntry::getFilePath)
            .map(MorePaths::pathWithUnixSeparators)
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<Path> remainingModuleFilepaths =
        modulesEdited
            .stream()
            .map(IjModule::getModuleImlFilePath)
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
                    .setFilePath(
                        Paths.get(
                            projectConfig.getProjectPaths().toProjectDirRelativeString(modulePath)))
                    .setFileUrl(projectConfig.getProjectPaths().toProjectDirRelativeUrl(modulePath))
                    .setGroup(projectConfig.getModuleGroupName())
                    .build()));

    // Write out the merged set to disk
    writeModulesIndex(finalModulesBuilder.build());
  }
}
