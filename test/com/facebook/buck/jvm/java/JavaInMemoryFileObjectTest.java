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

import com.facebook.buck.testutil.TestJar;
import com.facebook.buck.util.zip.JarBuilder;
import java.io.File;
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

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testJavaFileName() {
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("file://tmp/" + relativePath), relativePath, JavaFileObject.Kind.CLASS);

    String expectedName = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
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

    TestJar jar = writeToJar(inMemoryFileObject);
    assertEquals(7, jar.getZipEntries().size());
    assertEquals(7, jar.getEntriesContent().size());
    assertEquals("content", jar.getEntriesContent().get(6));
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

    TestJar jar = writeToJar(file1, file2);
    assertEquals(8, jar.getZipEntries().size());
    assertEquals(8, jar.getEntriesContent().size());
    assertEquals("file1Content", jar.getEntriesContent().get(6));
    assertEquals("file2Content", jar.getEntriesContent().get(7));
  }

  @Test
  public void testJarURIName() {
    String jarPath = "/tmp/test.jar";
    String relativePath = "com/facebook/buck/java/JavaInMemoryFileObjectTest.class";
    JavaInMemoryFileObject inMemoryFileObject =
        new JavaInMemoryFileObject(
            URI.create("jar:file://" + jarPath + "!/" + relativePath),
            relativePath,
            JavaFileObject.Kind.CLASS);

    String expectedName =
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

  public TestJar writeToJar(JavaInMemoryFileObject... entries) throws IOException {
    File jarFile = temp.newFile();
    JarBuilder jarBuilder = new JarBuilder();
    for (JavaInMemoryFileObject entry : entries) {
      entry.writeToJar(jarBuilder, "owner");
    }
    jarBuilder.createJarFile(jarFile.toPath());
    return new TestJar(jarFile);
  }
}
