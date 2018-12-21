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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.stringtemplate.v4.ST;

enum StringTemplateFile {
  ANDROID_MANIFEST("AndroidManifest.st"),
  MODULE_TEMPLATE("ij-module.st"),
  MODULE_INDEX_TEMPLATE("ij-module-index.st"),
  MISC_TEMPLATE("ij-misc.st"),
  LIBRARY_TEMPLATE("ij-library.st"),
  GENERATED_BY_IDEA_CLASS("GeneratedByIdeaClass.st");

  private static final char DELIMITER = '%';

  private final String fileName;

  StringTemplateFile(String fileName) {
    this.fileName = fileName;
  }

  public String getFileName() {
    return fileName;
  }

  /**
   * Not thread safe, see discussion in: https://github.com/antlr/stringtemplate4/issues/61 could be
   * made faster by sharing STGroup across threads using a supplier, see {@link
   * com.facebook.buck.parser.function.BuckPyFunction}. May be fixed in ST4.1
   */
  public synchronized ST getST() throws IOException {
    URL templateUrl = Resources.getResource(StringTemplateFile.class, "templates/" + fileName);
    String template = Resources.toString(templateUrl, StandardCharsets.UTF_8);
    return new ST(template, DELIMITER, DELIMITER);
  }

  public static boolean writeToFile(
      ProjectFilesystem projectFilesystem, ST contents, Path path, Path ideaConfigDir)
      throws IOException {

    byte[] renderedContentsBytes = contents.render().getBytes();
    projectFilesystem.createParentDirs(path);
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
        try (OutputStream outputStream = projectFilesystem.newFileOutputStream(tempFile)) {
          outputStream.write(renderedContentsBytes);
        }
        projectFilesystem.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        danglingTempFile = false;
      } finally {
        if (danglingTempFile) {
          projectFilesystem.deleteFileAtPath(tempFile);
        }
      }
    } else {
      try (OutputStream outputStream = projectFilesystem.newFileOutputStream(path)) {
        outputStream.write(renderedContentsBytes);
      }
    }
    return true;
  }
}
