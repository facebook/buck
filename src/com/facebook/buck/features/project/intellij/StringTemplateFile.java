/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

enum StringTemplateFile {
  ANDROID_MANIFEST("android_manifest"),
  MODULE_TEMPLATE("ij_module"),
  MODULE_INDEX_TEMPLATE("ij_module_index"),
  MISC_TEMPLATE("ij_misc"),
  LIBRARY_TEMPLATE("ij_library"),
  GENERATED_BY_IDEA_CLASS("generated_by_idea");

  private static final char DELIMITER = '%';

  private final String templateName;
  private static final Supplier<STGroup> groupSupplier =
      MoreSuppliers.memoize(
          () ->
              new STGroupFile(
                  Resources.getResource(StringTemplateFile.class, "templates.stg"),
                  StandardCharsets.UTF_8.name(),
                  DELIMITER,
                  DELIMITER));

  private StringTemplateFile(String templateName) {
    this.templateName = templateName;
  }

  public ST getST() throws IOException {
    STGroup group = groupSupplier.get();
    synchronized (group) {
      return group.getInstanceOf(templateName);
    }
  }

  public static ST getST(Path templatePath) throws IOException {
    return new ST(new String(Files.readAllBytes(templatePath)), DELIMITER, DELIMITER);
  }

  public static boolean writeToFile(
      ProjectFilesystem projectFilesystem, ST contents, Path path, Path ideaConfigDir)
      throws IOException {

    byte[] renderedContentsBytes = contents.render().getBytes();
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
}
