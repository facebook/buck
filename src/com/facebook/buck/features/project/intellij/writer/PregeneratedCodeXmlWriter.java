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
import com.facebook.buck.features.project.intellij.model.IjModuleAndroidFacet;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

/** This class takes care of writing the project data in the xml format. */
public class PregeneratedCodeXmlWriter extends PregeneratedCodeWriter {

  public PregeneratedCodeXmlWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem outFilesystem,
      IJProjectCleaner cleaner) {
    super(projectDataPreparer, projectConfig, outFilesystem, cleaner);
  }

  @Override
  protected void writeGeneratedByIdeaClassToFile(
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

  @Override
  protected void writeAndroidManifestToFile(
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
    byte[] renderedContentsBytes = contents.render().getBytes();
    IjProjectWriter.writeToFile(
        outFilesystem,
        renderedContentsBytes,
        fileToWrite,
        projectConfig.getProjectPaths().getIdeaConfigDir());
  }
}
