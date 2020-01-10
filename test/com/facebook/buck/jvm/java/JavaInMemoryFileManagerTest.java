/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TestJar;
import com.facebook.buck.util.zip.JarBuilder;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests {@link JavaInMemoryFileManager} */
public class JavaInMemoryFileManagerTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private JavaInMemoryFileManager inMemoryFileManager;

  @Before
  public void setUp() {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    inMemoryFileManager =
        new JavaInMemoryFileManager(
            ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, null),
            Paths.get(URI.create("file:///tmp/test.jar!/")),
            /*classesToBeRemovedFromJar */ RemoveClassesPatternsMatcher.EMPTY);
  }

  @Test
  public void testJavaFileName() throws Exception {
    JavaFileObject fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "com.facebook.buck.jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);

    assertEquals(JavaFileObject.Kind.CLASS, fileObject.getKind());
    assertEquals("com/facebook/buck/jvm/java/JavaFileParser.class", fileObject.getName());
  }

  @Test
  public void testWriteContent() throws Exception {
    JavaFileObject fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT, "JavaFileParser", JavaFileObject.Kind.CLASS, null);

    OutputStream stream = fileObject.openOutputStream();
    stream.write("Hello World!".getBytes());
    stream.close();

    TestJar jar = writeToJar();
    List<String> entries = jar.getEntriesContent();
    assertEquals(3, entries.size());
    assertEquals("Hello World!", entries.get(2));
  }

  @Test
  public void testFilesWrittenInSortedOrder() throws Exception {
    JavaFileObject fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT, "B.C", JavaFileObject.Kind.CLASS, null);
    fileObject.openOutputStream().close();

    fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT, "A", JavaFileObject.Kind.CLASS, null);
    fileObject.openOutputStream().close();

    fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT, "B", JavaFileObject.Kind.CLASS, null);
    fileObject.openOutputStream().close();

    fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT, "B$D", JavaFileObject.Kind.CLASS, null);
    fileObject.openOutputStream().close();

    TestJar jar = writeToJar();
    assertThat(
        jar.getZipEntries().stream().map(ZipEntry::getName).collect(Collectors.toList()),
        Matchers.contains(
            "META-INF/",
            JarFile.MANIFEST_NAME,
            "A.class",
            "B$D.class",
            "B.class",
            "B/",
            "B/C.class"));
  }

  @Test
  public void testIntermediateDirectoriesAreCreated() throws Exception {
    JavaFileObject fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);

    fileObject.openOutputStream().close();

    TestJar jar = writeToJar();
    List<String> zipEntries =
        jar.getZipEntries().stream().map(ZipEntry::getName).collect(Collectors.toList());
    assertThat(
        zipEntries,
        Matchers.contains(
            "META-INF/",
            JarFile.MANIFEST_NAME,
            "jvm/",
            "jvm/java/",
            "jvm/java/JavaFileParser.class"));
  }

  @Test
  public void testMultipleFilesInSamePackage() throws Exception {
    JavaFileObject fileObject1 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);

    JavaFileObject fileObject2 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaInMemoryFileManager",
            JavaFileObject.Kind.CLASS,
            null);

    fileObject1.openOutputStream().close();
    fileObject2.openOutputStream().close();

    TestJar jar = writeToJar();
    List<String> zipEntries =
        jar.getZipEntries().stream().map(ZipEntry::getName).collect(Collectors.toList());
    assertThat(
        zipEntries,
        Matchers.contains(
            "META-INF/",
            JarFile.MANIFEST_NAME,
            "jvm/",
            "jvm/java/",
            "jvm/java/JavaFileParser.class",
            "jvm/java/JavaInMemoryFileManager.class"));
  }

  @Test
  public void testIsNotSameFile() throws Exception {
    JavaFileObject fileObject1 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);

    JavaFileObject fileObject2 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaInMemoryFileManager",
            JavaFileObject.Kind.CLASS,
            null);

    assertFalse(inMemoryFileManager.isSameFile(fileObject1, fileObject2));
  }

  @Test
  public void testIsSameFile() throws Exception {
    JavaFileObject fileObject1 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);

    JavaFileObject fileObject2 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);

    assertTrue(inMemoryFileManager.isSameFile(fileObject1, fileObject2));
  }

  @Test
  public void testNonRecursiveListOperationReturnsNewlyCreatedFile() throws Exception {
    JavaFileObject fileObject1 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);
    Iterator<JavaFileObject> nonRecursiveIterable =
        inMemoryFileManager
            .list(
                StandardLocation.CLASS_OUTPUT,
                "jvm.java",
                Collections.singleton(JavaFileObject.Kind.CLASS),
                false)
            .iterator();

    assertEquals(fileObject1, nonRecursiveIterable.next());
    assertFalse(nonRecursiveIterable.hasNext());
  }

  @Test
  public void testNonRecursiveListOperationDoesntReturnNewlyCreatedFileOnOtherDir()
      throws Exception {
    JavaFileObject fileObject1 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);
    assertEquals("jvm/java/JavaFileParser.class", fileObject1.getName());
    Iterator<JavaFileObject> recursiveIterable =
        inMemoryFileManager
            .list(
                StandardLocation.CLASS_OUTPUT,
                "jvm",
                Collections.singleton(JavaFileObject.Kind.CLASS),
                false)
            .iterator();
    assertFalse(recursiveIterable.hasNext());
  }

  @Test
  public void testRecursiveListOperationReturnsNewlyCreatedFile() throws Exception {
    JavaFileObject fileObject1 =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.CLASS,
            null);
    Iterator<JavaFileObject> recursiveIterable =
        inMemoryFileManager
            .list(
                StandardLocation.CLASS_OUTPUT,
                "jvm",
                Collections.singleton(JavaFileObject.Kind.CLASS),
                true)
            .iterator();

    assertEquals(fileObject1, recursiveIterable.next());
    assertFalse(recursiveIterable.hasNext());
  }

  @Test
  public void testGetFileForOutputWithoutOpeningWritesNothing() throws IOException {
    inMemoryFileManager.getFileForOutput(
        StandardLocation.CLASS_OUTPUT, "jvm.java", "JavaFileParser", null);

    TestJar jar = writeToJar();
    assertThat(
        jar.getZipEntries().stream().map(ZipEntry::getName).collect(Collectors.toList()),
        Matchers.contains("META-INF/", JarFile.MANIFEST_NAME));
  }

  @Test
  public void testGetJavaFileForOutputWithoutOpeningWritesNothing() throws IOException {
    inMemoryFileManager.getJavaFileForOutput(
        StandardLocation.CLASS_OUTPUT, "jvm.java.JavaFileParser", JavaFileObject.Kind.OTHER, null);

    TestJar jar = writeToJar();
    assertThat(
        jar.getZipEntries().stream().map(ZipEntry::getName).collect(Collectors.toList()),
        Matchers.contains("META-INF/", JarFile.MANIFEST_NAME));
  }

  @Test
  public void testWriteToStreamWithoutClosingWritesNothing() throws IOException {
    JavaFileObject fileObject =
        inMemoryFileManager.getJavaFileForOutput(
            StandardLocation.CLASS_OUTPUT,
            "jvm.java.JavaFileParser",
            JavaFileObject.Kind.OTHER,
            null);

    fileObject.openOutputStream().write("Hello".getBytes());

    TestJar jar = writeToJar();
    assertThat(
        jar.getZipEntries().stream().map(ZipEntry::getName).collect(Collectors.toList()),
        Matchers.contains("META-INF/", JarFile.MANIFEST_NAME));
  }

  private TestJar writeToJar() throws IOException {
    File jarFile = temp.newFile();
    JarBuilder jarBuilder = new JarBuilder();
    inMemoryFileManager.writeToJar(jarBuilder);
    jarBuilder.createJarFile(jarFile.toPath());
    return new TestJar(jarFile);
  }
}
