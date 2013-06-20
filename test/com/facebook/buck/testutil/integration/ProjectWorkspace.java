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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.Main;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.MoreFiles;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import org.junit.rules.TemporaryFolder;

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
 * a tmp directory according to the following rule:
 * <ul>
 *   <li>Files with the {@code .expected} extension will not be copied.
 * </ul>
 * After {@link #setUp()} is invoked, the test should invoke Buck in that directory. As this is an
 * integration test, we expect that files will be written as a result of invoking Buck.
 * <p>
 * After Buck has been run, invoke {@link #verify()} to verify that Buck wrote the correct files.
 * For each file in the testdata directory with the {@code .expected} extension, {@link #verify()}
 * will check that a file with the same relative path (but without the {@code .expected} extension)
 * exists in the tmp directory. If not, {@link org.junit.Assert#fail()} will be invoked.
 */
public class ProjectWorkspace {

  private static final String EXPECTED_SUFFIX = ".expected";

  private static final Function<Path, Path> BUILD_FILE_RENAME = new Function<Path, Path>() {
    @Override
    @Nullable
    public Path apply(Path path) {
      String fileName = path.getFileName().toString();
      if (fileName.endsWith(EXPECTED_SUFFIX)) {
        return null;
      } else {
        return path;
      }
    }
  };

  private boolean isSetUp = false;
  private final Path templatePath;
  private final File destDir;
  private final Path destPath;

  /**
   * @param templateDir The directory that contains the template version of the project.
   * @param temporaryFolder The directory where the clone of the template directory should be
   *     written. By requiring a {@link TemporaryFolder} rather than a {@link File}, we can ensure
   *     that JUnit will clean up the test correctly.
   */
  public ProjectWorkspace(File templateDir, TemporaryFolder temporaryFolder) {
    Preconditions.checkNotNull(templateDir);
    Preconditions.checkNotNull(temporaryFolder);
    this.templatePath = templateDir.toPath();
    this.destDir = temporaryFolder.getRoot();
    this.destPath = destDir.toPath();
  }

  /**
   * This will copy the template directory, renaming files named {@code BUCK.test} to {@code BUCK}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   */
  public void setUp() throws IOException {
    MoreFiles.copyRecursively(templatePath, destPath, BUILD_FILE_RENAME);
    isSetUp = true;
  }

  /**
   * Runs Buck with the specified list of command-line arguments.
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *   {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(String... args) throws IOException {
    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();

    Main main = new Main(stdout, stderr);
    int exitCode = main.runMainWithExitCode(destDir, args);

    return new ProcessResult(exitCode,
        stdout.getContentsAsString(Charsets.UTF_8),
        stderr.getContentsAsString(Charsets.UTF_8));
  }

  /** The result of running {@code buck} from the command line. */
  public static class ProcessResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private ProcessResult(int exitCode, String stdout, String stderr) {
      this.exitCode = exitCode;
      this.stdout = Preconditions.checkNotNull(stdout);
      this.stderr = Preconditions.checkNotNull(stderr);
    }

    public int getExitCode() {
      return exitCode;
    }

    public String getStdout() {
      return stdout;
    }

    public String getStderr() {
      return stderr;
    }
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
}
