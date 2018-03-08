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

import static javax.tools.StandardLocation.ANNOTATION_PROCESSOR_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import org.junit.Before;
import org.junit.Test;

public class ClassUsageTrackerTest {

  private static final FileSystem WINDOWS_FILE_SYSTEM =
      Jimfs.newFileSystem(Configuration.windows());
  private static final FileSystem UNIX_FILE_SYSTEM = Jimfs.newFileSystem(Configuration.unix());

  private static final String WINDOWS_FILE_NAME = "Windows";

  private static final String[] FILE_NAMES = {
    "A", "B", "C", "D", "E", "F", "NonJava", "OTHER", "SOURCE", "HTML"
  };
  private static final String SINGLE_FILE_NAME = "C";
  private static final String SINGLE_NON_JAVA_FILE_NAME = "NonJava";
  private static final File SINGLE_FILE = new File("C");
  private static final Path TEST_JAR_PATH = UNIX_FILE_SYSTEM.getPath("/test.jar");
  private static final Path WINDOWS_JAR_PATH = WINDOWS_FILE_SYSTEM.getPath("C:\\test.jar");

  private ClassUsageTracker tracker;
  private ListenableFileManager fileManager;
  private FakeStandardJavaFileManager fakeFileManager;

  @Before
  public void setUp() {
    tracker = new ClassUsageTracker();
    fakeFileManager = new FakeStandardJavaFileManager();
    fileManager = new ListenableFileManager(fakeFileManager);
    fileManager.addListener(tracker);

    fakeFileManager.addFile(WINDOWS_JAR_PATH, WINDOWS_FILE_NAME, JavaFileObject.Kind.CLASS);

    for (String fileName : FILE_NAMES) {
      fakeFileManager.addFile(TEST_JAR_PATH, fileName, JavaFileObject.Kind.CLASS);
    }
  }

  @Test
  public void testWindowsPathsDontBlowUp() throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, WINDOWS_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openInputStream();
    assertFilesRead(WINDOWS_JAR_PATH, WINDOWS_FILE_NAME);
  }

  @Test
  public void readingFilesFromListShouldBeTracked() throws IOException {
    for (JavaFileObject javaFileObject : fileManager.list(null, null, null, false)) {
      javaFileObject.openInputStream();
    }

    assertFilesRead(TEST_JAR_PATH, FILE_NAMES);
  }

  @Test
  public void readingFileFromGetJavaFileForInputShouldBeTracked() throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openInputStream();
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileFromGetJavaFileForOutputShouldBeTracked() throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForOutput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS, null);

    javaFileObject.openInputStream();
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingJavaFileFromGetFileForInputShouldBeTracked() throws IOException {
    FileObject fileObject = fileManager.getFileForInput(null, null, SINGLE_FILE_NAME);

    fileObject.openInputStream();
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingJavaFileFromGetFileForOutputShouldBeTracked() throws IOException {
    FileObject fileObject = fileManager.getFileForOutput(null, null, SINGLE_FILE_NAME, null);

    fileObject.openInputStream();
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingNonJavaFileFromGetFileForInputShouldBeTracked() throws IOException {
    FileObject fileObject = fileManager.getFileForInput(null, null, SINGLE_NON_JAVA_FILE_NAME);

    fileObject.openInputStream();
    assertTrue(fileWasRead(TEST_JAR_PATH, SINGLE_NON_JAVA_FILE_NAME));
  }

  @Test
  public void readingNonJavaFileFromGetFileForOutputShouldBeTracked() throws IOException {
    FileObject fileObject =
        fileManager.getFileForOutput(null, null, SINGLE_NON_JAVA_FILE_NAME, null);

    fileObject.openInputStream();
    assertTrue(fileWasRead(TEST_JAR_PATH, SINGLE_NON_JAVA_FILE_NAME));
  }

  @Test
  public void readingFileFromGetJavaFileObjectsFromFilesShouldNotBeTracked() throws IOException {
    Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjectsFromFiles(Lists.newArrayList(SINGLE_FILE));

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertNoFilesRead();
  }

  @Test
  public void readingAnnotationProcessorFilesFromListShouldNotBeTracked() throws IOException {
    Iterable<JavaFileObject> listIterator =
        fileManager.list(ANNOTATION_PROCESSOR_PATH, null, null, false);
    for (JavaFileObject javaFileObject : listIterator) {
      javaFileObject.openInputStream();
    }

    assertNoFilesRead();
  }

  @Test
  public void readingAnnotationProcessorFileFromGetJavaFileForOutputShouldNotBeTracked()
      throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForOutput(
            ANNOTATION_PROCESSOR_PATH, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS, null);

    javaFileObject.openInputStream();
    assertNoFilesRead();
  }

  @Test
  public void readingAnnotationProcessorFileFromGetJavaFileForInputShouldBeTracked()
      throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(
            ANNOTATION_PROCESSOR_PATH, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openInputStream();
    assertNoFilesRead();
  }

  @Test
  public void readingJavaAnnotationProcessorFileFromGetFileForInputShouldNotBeTracked()
      throws IOException {
    FileObject fileObject =
        fileManager.getFileForInput(ANNOTATION_PROCESSOR_PATH, null, SINGLE_FILE_NAME);

    fileObject.openInputStream();
    assertNoFilesRead();
  }

  @Test
  public void readingJavaAnnotationProcessorFileFromGetFileForOutputShouldNotBeTracked()
      throws IOException {
    FileObject fileObject =
        fileManager.getFileForOutput(ANNOTATION_PROCESSOR_PATH, null, SINGLE_FILE_NAME, null);

    fileObject.openInputStream();
    assertNoFilesRead();
  }

  @Test
  public void readingNonJavaAnnotationProcessorFileFromGetFileForInputShouldNotBeTracked()
      throws IOException {
    FileObject fileObject =
        fileManager.getFileForInput(ANNOTATION_PROCESSOR_PATH, null, SINGLE_NON_JAVA_FILE_NAME);

    fileObject.openInputStream();
    assertNoFilesRead();
  }

  @Test
  public void readingNonJavaAnnotationProcessorFileFromGetFileForOutputShouldNotBeTracked()
      throws IOException {
    FileObject fileObject =
        fileManager.getFileForOutput(
            ANNOTATION_PROCESSOR_PATH, null, SINGLE_NON_JAVA_FILE_NAME, null);

    fileObject.openInputStream();
    assertNoFilesRead();
  }

  @Test
  public void readingFileFromGetJavaFileObjectsFileOverloadShouldNotBeTracked() throws IOException {
    Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjects(SINGLE_FILE);

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertNoFilesRead();
  }

  @Test
  public void readingFileFromGetJavaFileObjectsFromStringsShouldNotBeTracked() throws IOException {
    Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjectsFromStrings(Lists.newArrayList(SINGLE_FILE_NAME));

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertNoFilesRead();
  }

  @Test
  public void readingFileFromGetJavaFileObjectsStringsOverloadShouldNotBeTracked()
      throws IOException {
    Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjects(SINGLE_FILE_NAME);

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertNoFilesRead();
  }

  @Test
  public void readingFileByOpeningStreamShouldBeTracked() throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openInputStream();
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileByOpeningReaderShouldBeTracked() throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openReader(false);
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileWithGetCharContentShouldBeTracked() throws IOException {
    JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.getCharContent(false);
    assertFilesRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void readingSourceFileShouldNotBeTracked() throws IOException {
    assertFalse(fileTypeIsTracked(JavaFileObject.Kind.SOURCE));
  }

  @Test
  public void readingHTMLFileShouldNotBeTracked() throws IOException {
    assertFalse(fileTypeIsTracked(JavaFileObject.Kind.HTML));
  }

  @Test
  public void readingOtherFileShouldNotBeTracked() throws IOException {
    assertFalse(fileTypeIsTracked(JavaFileObject.Kind.OTHER));
  }

  @Test
  public void readingAnonymousClassShouldNotBeTracked() throws IOException {
    String anonymousClassName = "Foo$3.class";
    fakeFileManager.addFile(TEST_JAR_PATH, anonymousClassName, JavaFileObject.Kind.CLASS);

    fileManager
        .getJavaFileForInput(null, anonymousClassName, JavaFileObject.Kind.CLASS)
        .getCharContent(false);

    assertNoFilesRead();
  }

  @Test
  public void readingLocalClassShouldNotBeTracked() throws IOException {
    String localClassName = "Foo$3SomeLocalCLass.class";
    fakeFileManager.addFile(TEST_JAR_PATH, localClassName, JavaFileObject.Kind.CLASS);

    fileManager
        .getJavaFileForInput(null, localClassName, JavaFileObject.Kind.CLASS)
        .getCharContent(false);

    assertNoFilesRead();
  }

  private boolean fileTypeIsTracked(JavaFileObject.Kind kind) throws IOException {
    JavaFileObject javaFileObject = fileManager.getJavaFileForInput(null, kind.toString(), kind);

    javaFileObject.openInputStream();

    return fileWasRead(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  private boolean fileWasRead(Path jarPath, String fileName) {
    ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();
    ImmutableSet<Path> paths = classUsageMap.get(jarPath);

    return paths.contains(Paths.get(fileName));
  }

  private void assertFilesRead(Path jarPath, String... files) {
    ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();

    ImmutableSet<Path> paths = classUsageMap.get(jarPath);

    assertEquals(files.length, paths.size());

    for (String file : files) {
      assertTrue(fileWasRead(jarPath, file));
    }
  }

  private void assertNoFilesRead() {
    assertEquals(0, tracker.getClassUsageMap().size());
  }
}
