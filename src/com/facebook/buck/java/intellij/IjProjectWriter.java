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

package com.facebook.buck.java.intellij;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;

import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Writes the serialized representations of IntelliJ project components to disk.
 */
public class IjProjectWriter {

  public static final char DELIMITER = '%';
  public static final Path IDEA_CONFIG_DIR_PREFIX = Paths.get(".idea");
  public static final Path LIBRARIES_PREFIX = IDEA_CONFIG_DIR_PREFIX.resolve("libraries");
  public static final Path MODULES_PREFIX = IDEA_CONFIG_DIR_PREFIX.resolve("modules");

  private enum StringTemplateFile {
    MODULE_TEMPLATE("ij-module.st"),
    MODULE_INDEX_TEMPLATE("ij-module-index.st"),
    LIBRARY_TEMPLATE("ij-library.st");

    private final String fileName;
    StringTemplateFile(String fileName) {
      this.fileName = fileName;
    }

    public String getFileName() {
      return fileName;
    }
  }

  private IjProjectTemplateDataPreparer projectDataPreparer;
  private ProjectFilesystem projectFilesystem;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      ProjectFilesystem projectFilesystem) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectFilesystem = projectFilesystem;
  }

  public void write() throws IOException {
    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      writeModule(module);
    }
    for (IjLibrary library : projectDataPreparer.getLibrariesToBeWritten()) {
      writeLibrary(library);
    }
    writeModulesIndex();
  }

  private void writeModule(IjModule module) throws IOException {
    projectFilesystem.mkdirs(MODULES_PREFIX);
    Path path = IjProjectTemplateDataPreparer.getModuleOutputFilePath(module.getName());

    ST moduleContents = getST(StringTemplateFile.MODULE_TEMPLATE);

    // TODO(mkosiba): support androidFacet.
    moduleContents.add("androidFacet", false);
    moduleContents.add("contentRoot", projectDataPreparer.getContentRoot(module));
    moduleContents.add("dependencies", projectDataPreparer.getDependencies(module));

    writeToFile(moduleContents, path);
  }

  private void writeLibrary(IjLibrary library) throws IOException {
    projectFilesystem.mkdirs(LIBRARIES_PREFIX);
    Path path = LIBRARIES_PREFIX.resolve(library.getName() + ".xml");

    ST contents = getST(StringTemplateFile.LIBRARY_TEMPLATE);
    contents.add("name", library.getName());
    contents.add("binaryJar", library.getBinaryJar().orNull());
    contents.add("classPaths", library.getClassPaths());
    contents.add("sourceJar", library.getSourceJar().orNull());
    contents.add("javadocUrl", library.getJavadocUrl().orNull());
    //TODO(mkosiba): support res and assets for aar.

    writeToFile(contents, path);
  }

  private void writeModulesIndex() throws IOException {
    projectFilesystem.mkdirs(IDEA_CONFIG_DIR_PREFIX);
    Path path = IDEA_CONFIG_DIR_PREFIX.resolve("modules.xml");

    ST moduleIndexContents = getST(StringTemplateFile.MODULE_INDEX_TEMPLATE);
    moduleIndexContents.add("modules", projectDataPreparer.getModuleIndexEntries());

    writeToFile(moduleIndexContents, path);
  }

  private static ST getST(StringTemplateFile file) throws IOException {
    URL templateUrl = Resources.getResource(IjProjectWriter.class, file.getFileName());
    String template = Resources.toString(templateUrl, StandardCharsets.UTF_8);
    return new ST(template, DELIMITER, DELIMITER);
  }

  @VisibleForTesting
  protected void writeToFile(ST contents, Path path) throws IOException {
    StringWriter stringWriter = new StringWriter();
    AutoIndentWriter noIndentWriter = new AutoIndentWriter(stringWriter);
    contents.write(noIndentWriter);
    byte[] renderedContentsBytes = noIndentWriter.toString().getBytes(StandardCharsets.UTF_8);
    if (projectFilesystem.exists(path)) {
      String fileSha1 = projectFilesystem.computeSha1(path);
      String contentsSha1 = Hashing.sha1().hashBytes(renderedContentsBytes).toString();
      if (fileSha1.equals(contentsSha1)) {
        return;
      }
    }

    boolean danglingTempFile = false;
    Path tempFile = projectFilesystem.createTempFile(
        projectFilesystem.getPathForRelativePath(IDEA_CONFIG_DIR_PREFIX),
        path.getFileName().toString(),
        ".tmp");
    try {
      danglingTempFile = true;
      try (OutputStream outputStream = projectFilesystem.newFileOutputStream(tempFile)) {
        outputStream.write(contents.render().getBytes());
      }
      projectFilesystem.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
      danglingTempFile = false;
    } finally {
      if (danglingTempFile) {
        projectFilesystem.deleteFileAtPath(tempFile);
      }
    }
  }
}
