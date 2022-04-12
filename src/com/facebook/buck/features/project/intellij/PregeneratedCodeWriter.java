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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

public class PregeneratedCodeWriter {

  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem outFilesystem;
  private final IJProjectCleaner cleaner;

  public PregeneratedCodeWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem outFilesystem,
      IJProjectCleaner cleaner) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.cleaner = cleaner;
    this.outFilesystem = outFilesystem;
  }

  public void write() throws IOException {
    if (projectConfig.isGeneratingDummyRDotJavaEnabled()) {
      projectDataPreparer
          .getModulesToBeWritten()
          .parallelStream()
          .forEach(
              module -> {
                try {
                  writeClassesGeneratedByIdea(module);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }

    if (!projectConfig.isGeneratingAndroidManifestEnabled()) {
      return;
    }
    if (projectConfig.isSharedAndroidManifestGenerationEnabled()) {
      // We have fewer AndroidManifest.xml to write so aggregate them first
      Map<Path, IjModuleAndroidFacet> androidFacetByManifestPath = new HashMap<>();
      for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
        module
            .getAndroidFacet()
            .ifPresent(
                androidFacet ->
                    androidFacet
                        .getAndroidManifestPath(outFilesystem, projectConfig)
                        .ifPresent(
                            manifestPath ->
                                androidFacetByManifestPath.put(manifestPath, androidFacet)));
      }
      androidFacetByManifestPath
          .entrySet()
          .parallelStream()
          .forEach(
              entry -> {
                try {
                  writeAndroidManifestToFile(entry.getValue(), entry.getKey());
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } else {
      projectDataPreparer
          .getModulesToBeWritten()
          .parallelStream()
          .forEach(
              module -> {
                try {
                  writeAndroidManifest(module);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  private void writeClassesGeneratedByIdea(IjModule module) throws IOException {
    Optional<IjModuleAndroidFacet> androidFacet = module.getAndroidFacet();
    if (!androidFacet.isPresent()) {
      return;
    }

    Optional<String> packageName = getResourcePackage(module, androidFacet.get());
    if (!packageName.isPresent()) {
      return;
    }

    writeGeneratedByIdeaClassToFile(androidFacet.get(), packageName.get(), "R", null);

    writeGeneratedByIdeaClassToFile(androidFacet.get(), packageName.get(), "Manifest", null);
  }

  private void writeAndroidManifest(IjModule module) throws IOException {
    IjModuleAndroidFacet androidFacet = module.getAndroidFacet().orElse(null);
    if (androidFacet == null || !androidFacet.shouldGenerateAndroidManifest()) {
      return;
    }

    Optional<Path> androidManifestPath =
        androidFacet.getAndroidManifestPath(outFilesystem, projectConfig);
    if (androidManifestPath.isEmpty()) {
      return;
    }

    writeAndroidManifestToFile(androidFacet, androidManifestPath.get());
  }

  private void writeGeneratedByIdeaClassToFile(
      IjModuleAndroidFacet androidFacet,
      String packageName,
      String className,
      @Nullable String content)
      throws IOException {

    ST contents =
        StringTemplateFile.GENERATED_BY_IDEA_CLASS
            .getST()
            .add("package", packageName)
            .add("className", className)
            .add("content", content);

    Path fileToWrite =
        androidFacet
            .getGeneratedSourcePath()
            .resolve(packageName.replace('.', '/'))
            .resolve(className + ".java");
    writeTemplateToFile(fileToWrite, contents);
  }

  private void writeAndroidManifestToFile(
      IjModuleAndroidFacet androidFacet, Path androidManifestPath) throws IOException {
    if (!androidFacet.shouldGenerateAndroidManifest()) {
      return;
    }
    String packageName = androidFacet.getPackageNameOrDefault(projectConfig).orElse(null);

    String minSdkVersion = androidFacet.getMinSdkVersionOrDefault(projectConfig).orElse(null);

    ST contents =
        StringTemplateFile.ANDROID_MANIFEST
            .getST()
            .add("package", packageName)
            .add("minSdkVersion", minSdkVersion)
            .add(
                "permissions",
                androidFacet.getPermissions().stream().sorted().collect(Collectors.toList()));
    writeTemplateToFile(androidManifestPath, contents);
  }

  private void writeTemplateToFile(Path fileToWrite, ST contents) throws IOException {
    cleaner.doNotDelete(fileToWrite);

    StringTemplateFile.writeToFile(
        outFilesystem, contents, fileToWrite, projectConfig.getProjectPaths().getIdeaConfigDir());
  }

  private Optional<String> getResourcePackage(IjModule module, IjModuleAndroidFacet androidFacet) {
    Optional<String> packageName = androidFacet.getPackageName();
    if (!packageName.isPresent()) {
      packageName = projectDataPreparer.getFirstResourcePackageFromDependencies(module);
    }
    return packageName;
  }
}
