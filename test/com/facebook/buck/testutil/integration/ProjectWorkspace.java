/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.testutil.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.MoreFiles;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import javax.annotation.Nullable;

/**
 * {@link ProjectWorkspace} is a directory that contains a Buck project, complete with build files.
 * <p>
 * When {@link #setUp()} is invoked, the project files are cloned from a directory of testdata into
 * a tmp directory according to the following rules:
 * <ul>
 *   <li>Files named {@code BUCK.test} will be copied and renamed to {@code BUCK}.
 *   <li>Files with the {@code .expected} extension will not be copied.
 * </ul>
 * After {@link #setUp()} is invoked, the test should invoke Buck in that directory. As this is an
 * integration test, we expect that files will be written as a result of invoking Buck.
 * <p>
 * After Buck has been run, invoke {@link #verify()} to verify that Buck wrote the correct files.
 * For each file in the testdata directory with the {@code .expected} extension, {@link #verify()}
 * will check that a file with the same relative path (but without the {@code .expected} extension)
 * exists in the tmp directory. If not, {@link org.junit.Assert#fail()} will be invoked.
 * <p>
 * Finally, {@link #tearDown()} should be invoked in the JUnit test's {@link org.junit.After}
 * method.
 */
public class ProjectWorkspace {

  private static final String TESTDATA_BUILD_RULES_FILE_NAME = "BUCK.test";

  private static final String EXPECTED_SUFFIX = ".expected";

  private static final Function<Path, Path> BUILD_FILE_RENAME = new Function<Path, Path>() {
    @Override
    @Nullable
    public Path apply(Path path) {
      String fileName = path.getFileName().toString();
      if (TESTDATA_BUILD_RULES_FILE_NAME.equals(fileName)) {
        File directory = path.getParent().toFile();
        return new File(directory, BuckConstant.BUILD_RULES_FILE_NAME).toPath();
      } else if (fileName.endsWith(EXPECTED_SUFFIX)) {
        return null;
      } else {
        return path;
      }
    }
  };

  private final Path templatePath;
  private final Path destPath;

  public ProjectWorkspace(File templateDir, File destDir) {
    Preconditions.checkNotNull(templateDir);
    Preconditions.checkArgument(templateDir.isDirectory());
    Preconditions.checkNotNull(destDir);
    this.templatePath = templateDir.toPath();
    this.destPath = destDir.toPath();
  }

  /**
   * This will copy the template directory, renaming files named {@code BUCK.test} to {@code BUCK}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   */
  public void setUp() throws IOException {
    MoreFiles.copyRecursively(templatePath, destPath, BUILD_FILE_RENAME);
  }

  /**
   * For every file in the template directory whose name ends in {@code .expected}, checks that an
   * equivalent file has been written in the same place under the destination directory.
   */
  public void verify() throws IOException {
    SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(EXPECTED_SUFFIX)) {
          // Get File for the file that should be written, but without the ".expected" suffix.
          Path generatedFileWithSuffix = destPath.resolve(templatePath.relativize(file));
          File directory = generatedFileWithSuffix.getParent().toFile();
          File observedFile = new File(directory, Files.getNameWithoutExtension(fileName));

          if (!observedFile.isFile()) {
            fail("Expected file " + observedFile + " could not be found.");
          }
          String expectedFileContent = Files.toString(file.toFile(), Charsets.UTF_8);
          String observedFileContent = Files.toString(observedFile, Charsets.UTF_8);
          assertEquals(
              String.format(
                  "Expected content of %s to match that of %s.",
                  expectedFileContent,
                  observedFileContent),
              expectedFileContent,
              observedFileContent);
        }
        return FileVisitResult.CONTINUE;
      }
    };
    java.nio.file.Files.walkFileTree(templatePath, copyDirVisitor);
  }

  /** Deletes the destination directory created by {@link #setUp()}. */
  public void tearDown() throws IOException {
    MoreFiles.deleteRecursively(destPath);
  }
}
