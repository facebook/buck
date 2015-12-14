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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.TestCustomZipOutputStream;

import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.util.concurrent.Semaphore;

import javax.tools.JavaFileObject;

/**
 * Tests {@link JavaInMemoryFileObject}
 */
public class JavaInMemoryFileObjectTest {

  private TestCustomZipOutputStream outputStream;
  private Semaphore semaphore;

  @Before
  public void setUp() {
    outputStream = new TestCustomZipOutputStream();
    semaphore = new Semaphore(1);
  }

  @Test
  public void testJavaFileName() throws Exception {
    JavaInMemoryFileObject inMemoryFileObject = new JavaInMemoryFileObject(
        "com.facebook.buck.java.JavaInMemoryFileObjectTest",
        JavaFileObject.Kind.CLASS,
        outputStream,
        semaphore);

    final String expectedName = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    assertEquals(expectedName, inMemoryFileObject.getName());
  }

  @Test
  public void testJavaFileContent() throws Exception {
    JavaInMemoryFileObject inMemoryFileObject = new JavaInMemoryFileObject(
        "com.facebook.buck.java.JavaInMemoryFileObjectTest",
        JavaFileObject.Kind.CLASS,
        outputStream,
        semaphore);

    OutputStream out = inMemoryFileObject.openOutputStream();
    out.write("content".getBytes());
    out.close();

    assertEquals(1, outputStream.getZipEntries().size());
    assertEquals(1, outputStream.getEntriesContent().size());
    assertEquals("content", outputStream.getEntriesContent().get(0));
  }

  @Test
  public void testMultipleJavaFiles() throws Exception {
    JavaInMemoryFileObject file1 = new JavaInMemoryFileObject(
        "com.facebook.buck.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        outputStream,
        semaphore);

    JavaInMemoryFileObject file2 = new JavaInMemoryFileObject(
        "com.facebook.buck.java.JavaLibrary",
        JavaFileObject.Kind.CLASS,
        outputStream,
        semaphore);

    OutputStream file1Out = file1.openOutputStream();
    file1Out.write("file1Content".getBytes());
    file1Out.close();

    OutputStream file2Out = file2.openOutputStream();
    file2Out.write("file2Content".getBytes());
    file2Out.close();

    assertEquals(2, outputStream.getZipEntries().size());
    assertEquals(2, outputStream.getEntriesContent().size());
    assertEquals("file1Content", outputStream.getEntriesContent().get(0));
    assertEquals("file2Content", outputStream.getEntriesContent().get(1));
  }
}
