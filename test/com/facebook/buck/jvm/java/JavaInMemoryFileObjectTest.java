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
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.TestCustomJarOutputStream;
import com.facebook.buck.zip.JarBuilder;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import javax.tools.JavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests {@link JavaInMemoryFileObject} */
public class JavaInMemoryFileObjectTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testJavaFileName() throws Exception {
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("file://tmp/" + relativePath), relativePath, JavaFileObject.Kind.CLASS);

    final String expectedName = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    assertEquals(expectedName, inMemoryFileObject.getName());
  }

  @Test
  public void testJavaFileContent() throws Exception {
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("file://tmp/" + relativePath), relativePath, JavaFileObject.Kind.CLASS);

    OutputStream out = inMemoryFileObject.openOutputStream();
    out.write("content".getBytes());
    out.close();

    TestCustomJarOutputStream outputStream = writeToJar(inMemoryFileObject);
    assertEquals(2, outputStream.getZipEntries().size());
    assertEquals(2, outputStream.getEntriesContent().size());
    assertEquals("content", outputStream.getEntriesContent().get(1));
  }

  @Test
  public void testMultipleJavaFiles() throws Exception {
    String relativePath = "com/facebook/buck/java/JavaFileParser.class";
    JavaInMemoryFileObject file1 =
        new JavaInMemoryFileObject(
            URI.create("file://tmp/" + relativePath), relativePath, JavaFileObject.Kind.CLASS);

    String relativePath2 = "com/facebook/buck/java/JavaLibrary.class";
    JavaInMemoryFileObject file2 =
        new JavaInMemoryFileObject(
            URI.create("file://tmp/" + relativePath2), relativePath2, JavaFileObject.Kind.CLASS);

    OutputStream file1Out = file1.openOutputStream();
    file1Out.write("file1Content".getBytes());
    file1Out.close();

    OutputStream file2Out = file2.openOutputStream();
    file2Out.write("file2Content".getBytes());
    file2Out.close();

    TestCustomJarOutputStream outputStream = writeToJar(file1, file2);
    assertEquals(3, outputStream.getZipEntries().size());
    assertEquals(3, outputStream.getEntriesContent().size());
    assertEquals("file1Content", outputStream.getEntriesContent().get(1));
    assertEquals("file2Content", outputStream.getEntriesContent().get(2));
  }

  @Test
  public void testJarURIName() throws Exception {
    String jarPath = "/tmp/test.jar";
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("jar:file://" + jarPath + "!/" + relativePath),
            relativePath,
            JavaFileObject.Kind.CLASS);

    final String expectedName =
        "jar:file:///tmp/test.jar!/com/facebook/buck/java/JavaInMemoryFileObjectTest.class";

    assertEquals(relativePath, inMemoryFileObject.getName());
    assertEquals(expectedName, inMemoryFileObject.toUri().toString());
  }

  @Test(expected = FileNotFoundException.class)
  public void testOpenForInputThrowsWhenNotWritten() throws Exception {
    String jarPath = "/tmp/test.jar";
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("jar:file://" + jarPath + "!/" + relativePath),
            relativePath,
            JavaFileObject.Kind.CLASS);

    try (InputStream stream = inMemoryFileObject.openInputStream()) {
      stream.read();
    }
  }

  @Test(expected = IOException.class)
  public void testOpenForOutputTwiceThrows() throws Exception {
    String jarPath = "/tmp/test.jar";
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("jar:file://" + jarPath + "!/" + relativePath),
            relativePath,
            JavaFileObject.Kind.CLASS);

    try (OutputStream stream = inMemoryFileObject.openOutputStream()) {
      stream.write("Hello World!".getBytes());
    } catch (IOException e) {
      fail();
    }
    try (OutputStream stream = inMemoryFileObject.openOutputStream()) {
      stream.write("Hello World!".getBytes());
    } catch (IOException e) {
      throw e;
    }
  }

  public TestCustomJarOutputStream writeToJar(JavaInMemoryFileObject... entries)
      throws IOException {
    TestCustomJarOutputStream os = new TestCustomJarOutputStream();
    JarBuilder jarBuilder = new JarBuilder();
    for (JavaInMemoryFileObject entry : entries) {
      entry.writeToJar(jarBuilder, "owner");
    }
    jarBuilder.appendToJarFile(temporaryFolder.newFile().toPath(), os);
    return os;
  }
}
