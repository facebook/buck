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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.FluentIterable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
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

    DefaultClassUsageFileWriter writerOne = new DefaultClassUsageFileWriter(outputOne);
    {
      StandardJavaFileManager wrappedFileManager = writerOne.wrapFileManager(fakeFileManager);
      for (JavaFileObject javaFileObject : wrappedFileManager.list(null, null, null, false)) {
        javaFileObject.openInputStream();
      }
    }
    writerOne.writeFile(filesystem);

    DefaultClassUsageFileWriter writerTwo = new DefaultClassUsageFileWriter(outputTwo);
    {
      StandardJavaFileManager wrappedFileManager = writerTwo.wrapFileManager(fakeFileManager);
      Iterable<JavaFileObject> fileObjects = wrappedFileManager.list(null, null, null, false);
      for (JavaFileObject javaFileObject : FluentIterable.from(fileObjects).toList().reverse()) {
        javaFileObject.openInputStream();
      }
    }
    writerTwo.writeFile(filesystem);

    assertEquals(
        new String(Files.readAllBytes(outputOne)), new String(Files.readAllBytes(outputTwo)));
  }
}
