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

import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

public class PregeneratedCodeWriter {

  private final IjProjectTemplateDataPreparer projectDataPreparer;
  private final IjProjectConfig projectConfig;
  private final ProjectFilesystem projectFilesystem;

  public PregeneratedCodeWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectFilesystem = projectFilesystem;
  }

  public void write(IJProjectCleaner cleaner) throws IOException {
    if (projectConfig.isAutogenerateAndroidFacetSourcesEnabled()) {
      return;
    }

    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      writeClassesGeneratedByIdea(module, cleaner);
    }
  }

  private void writeClassesGeneratedByIdea(IjModule module, IJProjectCleaner cleaner)
      throws IOException {
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
        cleaner,
        packageName.get(),
        "BuildConfig",
        "  public final static boolean DEBUG = Boolean.parseBoolean(null);");

    writeGeneratedByIdeaClassToFile(androidFacet.get(), cleaner, packageName.get(), "R", null);

    writeGeneratedByIdeaClassToFile(
        androidFacet.get(), cleaner, packageName.get(), "Manifest", null);
  }

  private void writeGeneratedByIdeaClassToFile(
      IjModuleAndroidFacet androidFacet,
      IJProjectCleaner cleaner,
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

    cleaner.doNotDelete(fileToWrite);

    StringTemplateFile.writeToFile(projectFilesystem, contents, fileToWrite);
  }

  private Optional<String> getResourcePackage(IjModule module, IjModuleAndroidFacet androidFacet) {
    Optional<String> packageName = androidFacet.getPackageName();
    if (!packageName.isPresent()) {
      packageName = projectDataPreparer.getFirstResourcePackageFromDependencies(module);
    }
    return packageName;
  }
}
