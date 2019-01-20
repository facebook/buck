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

import com.facebook.buck.features.project.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

public class PregeneratedCodeWriter {

  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem outFilesystem;
  private final AndroidManifestParser androidManifestParser;
  private final IJProjectCleaner cleaner;

  public PregeneratedCodeWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem outFilesystem,
      AndroidManifestParser androidManifestParser,
      IJProjectCleaner cleaner) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.androidManifestParser = androidManifestParser;
    this.cleaner = cleaner;
    this.outFilesystem = outFilesystem;
  }

  public void write() throws IOException {
    if (!projectConfig.isAutogenerateAndroidFacetSourcesEnabled()) {
      for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
        writeClassesGeneratedByIdea(module);
      }
    }

    if (projectConfig.isGeneratingAndroidManifestEnabled()) {
      for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
        writeAndroidManifest(module);
      }
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

    writeGeneratedByIdeaClassToFile(
        androidFacet.get(),
        packageName.get(),
        "BuildConfig",
        "  public final static boolean DEBUG = Boolean.parseBoolean(null);");

    writeGeneratedByIdeaClassToFile(androidFacet.get(), packageName.get(), "R", null);

    writeGeneratedByIdeaClassToFile(androidFacet.get(), packageName.get(), "Manifest", null);
  }

  private void writeAndroidManifest(IjModule module) throws IOException {
    Optional<IjModuleAndroidFacet> androidFacet = module.getAndroidFacet();
    if (!androidFacet.isPresent()) {
      return;
    }

    Optional<String> packageName = androidFacet.get().discoverPackageName(androidManifestParser);
    if (!packageName.isPresent()) {
      return;
    }

    Optional<String> minSdkVersionFromManifest = androidFacet.get().getMinSdkVersion();
    Optional<String> defaultProjectMinSdkVersion = projectConfig.getMinAndroidSdkVersion();

    Optional<String> minSdkVersion = Optional.empty();
    if (minSdkVersionFromManifest.isPresent()) {
      minSdkVersion = minSdkVersionFromManifest;
    } else if (defaultProjectMinSdkVersion.isPresent()) {
      minSdkVersion = defaultProjectMinSdkVersion;
    }

    writeAndroidManifestToFile(androidFacet.get(), packageName.get(), minSdkVersion);
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

    writeTemplateToFile(androidFacet, className + ".java", packageName, contents);
  }

  private void writeAndroidManifestToFile(
      IjModuleAndroidFacet androidFacet, String packageName, Optional<String> minSdkVersion)
      throws IOException {

    ST contents = StringTemplateFile.ANDROID_MANIFEST.getST().add("package", packageName);

    contents.add("minSdkVersion", minSdkVersion.orElse(null));

    writeTemplateToFile(androidFacet, "AndroidManifest.xml", packageName, contents);
  }

  private void writeTemplateToFile(
      IjModuleAndroidFacet androidFacet, String filename, String packageName, ST contents)
      throws IOException {
    Path fileToWrite =
        androidFacet
            .getGeneratedSourcePath()
            .resolve(packageName.replace('.', '/'))
            .resolve(filename);

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
