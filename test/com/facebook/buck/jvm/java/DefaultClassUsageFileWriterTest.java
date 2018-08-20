/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class DefaultClassUsageFileWriterTest {

  private static final String OTHER_FILE_NAME = JavaFileObject.Kind.OTHER.toString();
  private static final String SOURCE_FILE_NAME = JavaFileObject.Kind.SOURCE.toString();
  private static final String HTML_FILE_NAME = JavaFileObject.Kind.HTML.toString();

  private static final String[] FILE_NAMES = {"A", "B", "C", "D", "E", "F"};
  private static final String SINGLE_NON_JAVA_FILE_NAME = "NonJava";

  @Test
  public void fileReadOrderDoesntAffectClassesUsedOutput() throws IOException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createRealTempFilesystem();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(filesystem);
    Path testJarPath = filesystem.getPathForRelativePath("test.jar");
    Path testTwoJarPath = filesystem.getPathForRelativePath("test2.jar");

    Path outputOne = filesystem.getPathForRelativePath("used-classes-one.json");
    Path outputTwo = filesystem.getPathForRelativePath("used-classes-two.json");

    FakeStandardJavaFileManager fakeFileManager = new FakeStandardJavaFileManager();
    fakeFileManager.addFile(testJarPath, OTHER_FILE_NAME, JavaFileObject.Kind.OTHER);
    fakeFileManager.addFile(testJarPath, SOURCE_FILE_NAME, JavaFileObject.Kind.SOURCE);
    fakeFileManager.addFile(testJarPath, HTML_FILE_NAME, JavaFileObject.Kind.HTML);
    fakeFileManager.addFile(testJarPath, SINGLE_NON_JAVA_FILE_NAME, JavaFileObject.Kind.OTHER);
    for (String fileName : FILE_NAMES) {
      fakeFileManager.addFile(testJarPath, fileName, JavaFileObject.Kind.CLASS);
    }
    for (String fileName : FILE_NAMES) {
      fakeFileManager.addFile(testTwoJarPath, fileName, JavaFileObject.Kind.CLASS);
    }

    DefaultClassUsageFileWriter writerOne = new DefaultClassUsageFileWriter();
    ClassUsageTracker trackerOne = new ClassUsageTracker();
    {
      ListenableFileManager wrappedFileManager = new ListenableFileManager(fakeFileManager);
      wrappedFileManager.addListener(trackerOne);
      for (JavaFileObject javaFileObject : wrappedFileManager.list(null, null, null, false)) {
        javaFileObject.openInputStream();
      }
    }
    writerOne.writeFile(trackerOne, outputOne, filesystem, cellPathResolver);

    DefaultClassUsageFileWriter writerTwo = new DefaultClassUsageFileWriter();
    ClassUsageTracker trackerTwo = new ClassUsageTracker();
    {
      ListenableFileManager wrappedFileManager = new ListenableFileManager(fakeFileManager);
      wrappedFileManager.addListener(trackerTwo);
      Iterable<JavaFileObject> fileObjects = wrappedFileManager.list(null, null, null, false);
      for (JavaFileObject javaFileObject : FluentIterable.from(fileObjects).toList().reverse()) {
        javaFileObject.openInputStream();
      }
    }
    writerTwo.writeFile(trackerTwo, outputTwo, filesystem, cellPathResolver);

    assertEquals(
        new String(Files.readAllBytes(outputOne)), new String(Files.readAllBytes(outputTwo)));
  }

  @Test
  public void classUsageFileWriterHandlesCrossCell() throws IOException {
    ProjectFilesystem homeFs = FakeProjectFilesystem.createRealTempFilesystem();
    ProjectFilesystem awayFs = FakeProjectFilesystem.createRealTempFilesystem();
    ProjectFilesystem externalFs = FakeProjectFilesystem.createRealTempFilesystem();

    CellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(
            homeFs.getRootPath(), ImmutableMap.of("AwayCell", awayFs.getRootPath()));
    Path testJarPath = homeFs.getPathForRelativePath("home.jar");
    Path testTwoJarPath = awayFs.getPathForRelativePath("away.jar");
    Path externalJarPath = externalFs.getPathForRelativePath("external.jar");

    Path outputOne = homeFs.getPathForRelativePath("used-classes-one.json");

    FakeStandardJavaFileManager fakeFileManager = new FakeStandardJavaFileManager();
    fakeFileManager.addFile(testJarPath, "HomeCellClass", JavaFileObject.Kind.CLASS);
    fakeFileManager.addFile(testTwoJarPath, "AwayCellClass", JavaFileObject.Kind.CLASS);
    fakeFileManager.addFile(externalJarPath, "ExternalClass", JavaFileObject.Kind.CLASS);

    DefaultClassUsageFileWriter writer = new DefaultClassUsageFileWriter();
    ClassUsageTracker trackerOne = new ClassUsageTracker();
    {
      ListenableFileManager wrappedFileManager = new ListenableFileManager(fakeFileManager);
      wrappedFileManager.addListener(trackerOne);
      for (JavaFileObject javaFileObject : wrappedFileManager.list(null, null, null, false)) {
        javaFileObject.openInputStream();
      }
    }
    writer.writeFile(trackerOne, outputOne, homeFs, cellPathResolver);

    // The xcell file should appear relative to the "home" filesystem, and the external class
    // which is not under any cell in the project should not appear at all.
    Path expectedAwayCellPath =
        homeFs
            .getRootPath()
            .getRoot()
            .resolve("AwayCell")
            .resolve(awayFs.relativize(testTwoJarPath));
    Escaper.Quoter quoter =
        Platform.detect() == Platform.WINDOWS
            ? Escaper.Quoter.DOUBLE_WINDOWS_JAVAC
            : Escaper.Quoter.DOUBLE;
    String escapedExpectedAwayCellPath = quoter.quote(expectedAwayCellPath.toString());
    assertThat(
        new String(Files.readAllBytes(outputOne)),
        new JsonMatcher(
            String.format(
                "{" + "\"home.jar\": [\"HomeCellClass\"], %s: [ \"AwayCellClass\" ]" + "}",
                escapedExpectedAwayCellPath)));
  }
}
