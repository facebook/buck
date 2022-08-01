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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** This class takes care of writing the project data in the json format. */
public class PregeneratedCodeJsonWriter extends PregeneratedCodeWriter {

  private static final ObjectMapper mapper = new ObjectMapper();

  public PregeneratedCodeJsonWriter(
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

    JsonMap map =
        new JsonMap()
            .put("package", packageName)
            .put("className", className)
            .put("content", content);

    Path fileToWrite =
        androidFacet
            .getGeneratedSourcePath()
            .resolve(packageName.replace('.', '/'))
            .resolve(className + ".json");
    writeTemplateToFile(fileToWrite, map.get());
  }

  @Override
  protected void writeAndroidManifestToFile(
      IjModuleAndroidFacet androidFacet, Path androidManifestPath) throws IOException {
    if (!androidFacet.shouldGenerateAndroidManifest()) {
      return;
    }
    String packageName = androidFacet.getPackageNameOrDefault(projectConfig).orElse(null);

    String minSdkVersion = androidFacet.getMinSdkVersionOrDefault(projectConfig).orElse(null);

    JsonMap map =
        new JsonMap()
            .put("package", packageName)
            .put("minSdkVersion", minSdkVersion)
            .put(
                "permissions",
                androidFacet.getPermissions().stream().sorted().collect(Collectors.toList()));
    writeTemplateToFile(androidManifestPath, map.get());
  }

  private void writeTemplateToFile(Path fileToWrite, Object contents) throws IOException {
    cleaner.doNotDelete(fileToWrite);

    byte[] renderedContentsBytes =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(contents).getBytes();

    IjProjectWriter.writeToFile(
        outFilesystem,
        renderedContentsBytes,
        fileToWrite,
        projectConfig.getProjectPaths().getIdeaConfigDir());
  }
}
