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

package com.facebook.buck.android.resources.strings;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Copy filtered string resources (values/strings.xml) files to output directory. These will be used
 * by i18n to map resource_id to fbt_hash with resource_name as the intermediary
 */
public class StringResourcesUtils {
  private static final String VALUES = "values";
  private static final String STRINGS_XML = "strings.xml";
  private static final String NEW_RES_DIR_FORMAT = "%04x";
  // "4 digit hex" => 65536 files

  /*
   * Copy all strings.xml under resDir/values to output Path
   */
  public static void copyResources(
      AbsPath projectRoot, ImmutableList<Path> resDirs, Path outputDirPath) throws IOException {
    int i = 0;
    for (Path resDir : resDirs) {
      Path stringsFilePath = resDir.resolve(VALUES).resolve(STRINGS_XML);
      if (ProjectFilesystemUtils.exists(projectRoot, stringsFilePath)) {
        // create <output_dir>/<new_res_dir>/values
        Path newStringsFileDir =
            outputDirPath.resolve(String.format(NEW_RES_DIR_FORMAT, i++)).resolve(VALUES);
        ProjectFilesystemUtils.mkdirs(projectRoot, newStringsFileDir);
        // copy <res_dir>/values/strings.xml ->
        // <output_dir>/<new_res_dir>/values/strings.xml
        ProjectFilesystemUtils.copyFile(
            projectRoot, stringsFilePath, newStringsFileDir.resolve(STRINGS_XML));
      }
    }
  }

  /**
   * Copy all strings.xml under resDir/values-xx to output Path
   *
   * @param resDirs List of directories that contains values-xx/strings.
   */
  public static void copyVoltronStringResources(
      AbsPath projectRoot,
      ImmutableList<Path> resDirs,
      ProjectFilesystem projectFilesystem,
      Path outputDirPath)
      throws IOException {
    int i = 0;
    for (Path resDir : resDirs) {
      ImmutableCollection<Path> dirs = projectFilesystem.getDirectoryContents(resDir);
      boolean containNonEnglishStrings = false;
      for (Path dir : dirs) {
        String filename = dir.getFileName().toString();
        if (filename.startsWith(VALUES) && !filename.equals(VALUES)) {
          Path stringsFilePath = resDir.resolve(filename).resolve(STRINGS_XML);
          if (ProjectFilesystemUtils.exists(projectRoot, stringsFilePath)) {
            // create <output_dir>/<new_res_dir>/values-xx
            Path newStringsFileDir =
                outputDirPath.resolve(String.format(NEW_RES_DIR_FORMAT, i)).resolve(filename);
            ProjectFilesystemUtils.mkdirs(projectRoot, newStringsFileDir);
            // copy <res_dir>/values-es/strings.xml ->
            // <output_dir>/<new_res_dir>/values-es/strings.xml
            ProjectFilesystemUtils.copyFile(
                projectRoot, stringsFilePath, newStringsFileDir.resolve(STRINGS_XML));
            containNonEnglishStrings = true;
          }
        }
      }
      if (containNonEnglishStrings) {
        ++i;
      }
    }
  }
}
