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

import com.facebook.buck.features.project.intellij.IJProjectCleaner;
import com.facebook.buck.features.project.intellij.IjProjectTemplateDataPreparer;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** This class is responsible for writing idea classes and AndroidManifest files. */
public abstract class PregeneratedCodeWriter {
  protected final IjProjectTemplateDataPreparer projectDataPreparer;
  protected final IjProjectConfig projectConfig;
  protected final ProjectFilesystem outFilesystem;
  protected final IJProjectCleaner cleaner;

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

  /** Driver function that writes the idea class and manifest files. */
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
      // We have fewer AndroidManifest.{xml,json} to write so aggregate them first.
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

  protected abstract void writeGeneratedByIdeaClassToFile(
      IjModuleAndroidFacet androidFacet,
      String packageName,
      String className,
      @Nullable String content)
      throws IOException;

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

  protected abstract void writeAndroidManifestToFile(
      IjModuleAndroidFacet androidFacet, Path androidManifestPath) throws IOException;

  /** Get resource package where the androidFacet should be located. */
  protected Optional<String> getResourcePackage(
      IjModule module, IjModuleAndroidFacet androidFacet) {
    Optional<String> packageName = androidFacet.getPackageName();
    if (!packageName.isPresent()) {
      packageName = projectDataPreparer.getFirstResourcePackageFromDependencies(module);
    }
    return packageName;
  }
}
