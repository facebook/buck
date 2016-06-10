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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TestCustomZipOutputStream;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Tests {@link JavaInMemoryFileManager}
 */
public class JavaInMemoryFileManagerTest {

  private JavaInMemoryFileManager inMemoryFileManager;
  private TestCustomZipOutputStream outputStream;

  @Before
  public void setUp() {
    outputStream = new TestCustomZipOutputStream();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    inMemoryFileManager = new JavaInMemoryFileManager(
        ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, null),
        outputStream,
        /*classesToBeRemoved */ ImmutableList.<Pattern>of());
  }

  @Test
  public void testJavaFileName() throws Exception {
    JavaFileObject fileObject = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "com.facebook.buck.jvm.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    assertEquals(JavaFileObject.Kind.CLASS, fileObject.getKind());
    assertEquals("com/facebook/buck/jvm/java/JavaFileParser.class", fileObject.getName());
  }

  @Test
  public void testWriteContent() throws Exception {
    JavaFileObject fileObject = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    OutputStream stream = fileObject.openOutputStream();
    stream.write("Hello World!".getBytes());
    stream.close();

    List<String> entries = outputStream.getEntriesContent();
    assertEquals(1, entries.size());
    assertEquals("Hello World!", entries.get(0));
  }

  @Test
  public void testIntermediateDirectoriesAreCreated() throws Exception {
    JavaFileObject fileObject = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    fileObject.openOutputStream().close();

    List<ZipEntry> zipEntries = outputStream.getZipEntries();
    assertEquals(3, zipEntries.size());
    assertEquals("jvm/", zipEntries.get(0).getName());
    assertEquals("jvm/java/", zipEntries.get(1).getName());
    assertEquals("jvm/java/JavaFileParser.class", zipEntries.get(2).getName());
  }

  @Test
  public void testMultipleFilesInSamePackage() throws Exception {
    JavaFileObject fileObject1 = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    JavaFileObject fileObject2 = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaInMemoryFileManager",
        JavaFileObject.Kind.CLASS,
        null);

    fileObject1.openOutputStream().close();
    fileObject2.openOutputStream().close();

    List<ZipEntry> zipEntries = outputStream.getZipEntries();
    assertEquals(4, zipEntries.size());
    assertEquals("jvm/", zipEntries.get(0).getName());
    assertEquals("jvm/java/", zipEntries.get(1).getName());
    assertEquals("jvm/java/JavaFileParser.class", zipEntries.get(2).getName());
    assertEquals("jvm/java/JavaInMemoryFileManager.class", zipEntries.get(3).getName());
  }

  @Test
  public void testIsNotSameFile() throws Exception {
    JavaFileObject fileObject1 = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    JavaFileObject fileObject2 = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaInMemoryFileManager",
        JavaFileObject.Kind.CLASS,
        null);

    assertFalse(inMemoryFileManager.isSameFile(fileObject1, fileObject2));
  }

  @Test
  public void testIsSameFile() throws Exception {
    JavaFileObject fileObject1 = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    JavaFileObject fileObject2 = inMemoryFileManager.getJavaFileForOutput(
        locationOf("src"),
        "jvm.java.JavaFileParser",
        JavaFileObject.Kind.CLASS,
        null);

    assertTrue(inMemoryFileManager.isSameFile(fileObject1, fileObject2));
  }

  private static Location locationOf(final String name) {
    return new Location() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public boolean isOutputLocation() {
        return true;
      }
    };
  }
}
