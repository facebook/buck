/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class ListenableFileManagerTest {

  private static final FileSystem UNIX_FILE_SYSTEM = Jimfs.newFileSystem(Configuration.unix());
  private static final String[] FILE_NAMES = {
    "A", "B", "C", "D", "E", "F", "NonJava", "OTHER", "SOURCE", "HTML"
  };
  private static final Path TEST_JAR_PATH = UNIX_FILE_SYSTEM.getPath("/test.jar");
  private static final String SINGLE_FILE_NAME = "C";
  private static final String SINGLE_NON_JAVA_FILE_NAME = "NonJava";

  private ListenableFileManager fileManager;
  private FakeStandardJavaFileManager fakeFileManager;
  private List<FileObject> filesRead = new ArrayList<>();

  @Before
  public void setUp() {
    fakeFileManager = new FakeStandardJavaFileManager();
    fileManager =
        new ListenableFileManager(
            fakeFileManager,
            new FileManagerListener() {
              @Override
              public void onFileRead(JavaFileObject file) {
                filesRead.add(file);
              }
            });

    for (String fileName : FILE_NAMES) {
      fakeFileManager.addFile(TEST_JAR_PATH, fileName, JavaFileObject.Kind.CLASS);
    }
  }

  @Test
  public void testOpenInputStreamReportsRead() throws IOException {
    fileManager
        .getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS)
        .openInputStream();

    assertFilesRead("C");
  }

  @Test
  public void testOpenReaderReportsRead() throws IOException {
    fileManager
        .getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS)
        .openReader(true);

    assertFilesRead("C");
  }

  @Test
  public void testGetCharContentReportsRead() throws IOException {
    fileManager
        .getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS)
        .getCharContent(true);

    assertFilesRead("C");
  }

  private void assertFilesRead(String... fileNames) {
    assertThat(fileNames(filesRead), Matchers.arrayContaining(fileNames));
  }

  private String[] fileNames(List<FileObject> files) {
    return files.stream().map(FileObject::getName).toArray(String[]::new);
  }
}
