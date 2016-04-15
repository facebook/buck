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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;

import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public class ClassUsageTrackerTest {

  private static final String[] FILE_NAMES = { "A", "B", "C", "D", "E", "F" };
  private static final Set<String> FILE_NAMES_SET = Sets.newHashSet(FILE_NAMES);
  private static final String SINGLE_FILE_NAME = "C";
  private static final String SINGLE_NON_JAVA_FILE_NAME = "NonJava";
  private static final File SINGLE_FILE = new File("C");
  private static final String TEST_JAR_NAME = "test.jar";

  private ClassUsageTracker tracker;
  private StandardJavaFileManager fileManager;

  @Before
  public void setUp() {
    tracker = new ClassUsageTracker();
    fileManager = tracker.wrapFileManager(new FakeStandardJavaFileManager());
  }

  @Test
  public void readingFilesFromListShouldBeTracked() throws IOException {
    for (JavaFileObject javaFileObject : fileManager.list(null, null, null, false)) {
      javaFileObject.openInputStream();
    }

    assertFilesRead(FILE_NAMES);
  }

  @Test
  public void readingFileFromGetJavaFileForInputShouldBeTracked() throws IOException {
    final JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openInputStream();
    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileFromGetJavaFileForOutputShouldBeTracked() throws IOException {
    final JavaFileObject javaFileObject =
        fileManager.getJavaFileForOutput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS, null);

    javaFileObject.openInputStream();
    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingJavaFileFromGetFileForInputShouldBeTracked() throws IOException {
    final FileObject fileObject =
        fileManager.getFileForInput(null, null, SINGLE_FILE_NAME);

    fileObject.openInputStream();
    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingJavaFileFromGetFileForOutputShouldBeTracked() throws IOException {
    final FileObject fileObject =
        fileManager.getFileForOutput(null, null, SINGLE_FILE_NAME, null);

    fileObject.openInputStream();
    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingNonJavaFileFromGetFileForInputShouldNotBeTracked() throws IOException {
    final FileObject fileObject =
        fileManager.getFileForInput(null, null, SINGLE_NON_JAVA_FILE_NAME);

    fileObject.openInputStream();
    assertFalse(fileWasRead(SINGLE_NON_JAVA_FILE_NAME));
  }

  @Test
  public void readingNonJavaFileFromGetFileForOutputShouldNotBeTracked() throws IOException {
    final FileObject fileObject =
        fileManager.getFileForOutput(null, null, SINGLE_NON_JAVA_FILE_NAME, null);

    fileObject.openInputStream();
    assertFalse(fileWasRead(SINGLE_NON_JAVA_FILE_NAME));
  }

  @Test
  public void readingFileFromGetJavaFileObjectsFromFilesShouldBeTracked() throws IOException {
    final Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjectsFromFiles(Lists.newArrayList(SINGLE_FILE));

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileFromGetJavaFileObjectsFileOverloadShouldBeTracked() throws IOException {
    final Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjects(SINGLE_FILE);

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileFromGetJavaFileObjectsFromStringsShouldBeTracked() throws IOException {
    final Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjectsFromStrings(Lists.newArrayList(SINGLE_FILE_NAME));

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileFromGetJavaFileObjectsStringsOverloadShouldBeTracked() throws IOException {
    final Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjects(SINGLE_FILE_NAME);

    for (JavaFileObject javaFileObject : javaFileObjects) {
      javaFileObject.openInputStream();
    }

    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileByOpeningStreamShouldBeTracked() throws IOException {
    final JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openInputStream();
    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileByOpeningReaderShouldBeTracked() throws IOException {
    final JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.openReader(false);
    assertFilesRead(SINGLE_FILE_NAME);
  }

  @Test
  public void readingFileWithGetCharContentShouldBeTracked() throws IOException {
    final JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, JavaFileObject.Kind.CLASS);

    javaFileObject.getCharContent(false);
    assertFilesRead(SINGLE_FILE_NAME);
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

  private boolean fileTypeIsTracked(JavaFileObject.Kind kind) throws IOException {
    final JavaFileObject javaFileObject =
        fileManager.getJavaFileForInput(null, SINGLE_FILE_NAME, kind);

    javaFileObject.openInputStream();

    return fileWasRead(SINGLE_FILE_NAME);
  }

  private boolean fileWasRead(String fileName) {
    final ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();
    final ImmutableSet<Path> paths = classUsageMap.get(Paths.get(TEST_JAR_NAME));

    return paths.contains(Paths.get(fileName));
  }

  private void assertFilesRead(String... files) {
    final ImmutableSetMultimap<Path, Path> classUsageMap = tracker.getClassUsageMap();

    final ImmutableSet<Path> paths = classUsageMap.get(Paths.get(TEST_JAR_NAME));

    assertEquals(files.length, paths.size());

    for (String file : files) {
      assertTrue(fileWasRead(file));
    }
  }

  private static String getJarPath(String filePath) {
    return String.format("jar:file:%s!/%s", TEST_JAR_NAME, filePath);
  }

  private static class FakeStandardJavaFileManager implements StandardJavaFileManager {

    private static final Function<File, JavaFileObject> fileToJavaFileObject =
        new Function<File, JavaFileObject>() {
          @Nullable
          @Override
          public JavaFileObject apply(@Nullable File input) {
            return new FakeJavaFileObject(input.getName(), JavaFileObject.Kind.CLASS);
          }
        };

    private static final Function<String, JavaFileObject> stringToJavaFileObject =
        new Function<String, JavaFileObject>() {
          @Nullable
          @Override
          public JavaFileObject apply(@Nullable String input) {
            return new FakeJavaFileObject(input, JavaFileObject.Kind.CLASS);
          }
        };

    @Override
    public Iterable<JavaFileObject> list(
        Location location,
        String packageName,
        Set<JavaFileObject.Kind> kinds,
        boolean recurse) throws IOException {
      return Lists.newArrayList(getJavaFileObjects(FILE_NAMES));
    }

    @Override
    public JavaFileObject getJavaFileForInput(
        Location location,
        String className,
        JavaFileObject.Kind kind) throws IOException {
      return new FakeJavaFileObject(className, kind);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        Location location,
        String className,
        JavaFileObject.Kind kind,
        FileObject sibling) throws IOException {
      return new FakeJavaFileObject(className, kind);
    }

    @Override
    public FileObject getFileForInput(
        Location location,
        String packageName,
        String relativeName) throws IOException {
      if (FILE_NAMES_SET.contains(relativeName)) {
        return getJavaFileForInput(location, relativeName, JavaFileObject.Kind.CLASS);
      }

      return new FakeFileObject(relativeName);
    }

    @Override
    public FileObject getFileForOutput(
        Location location,
        String packageName,
        String relativeName,
        FileObject sibling) throws IOException {
      if (FILE_NAMES_SET.contains(relativeName)) {
        return getJavaFileForOutput(location, relativeName, JavaFileObject.Kind.CLASS, null);
      }

      return new FakeFileObject(relativeName);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(
        Iterable<? extends File> files) {
      return Iterables.transform(
          files,
          fileToJavaFileObject);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
      return Iterables.transform(
          Lists.newArrayList(files),
          fileToJavaFileObject);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(
        Iterable<String> names) {
      return Iterables.transform(
          names,
          stringToJavaFileObject);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
      return Iterables.transform(
          Lists.newArrayList(names),
          stringToJavaFileObject);
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasLocation(Location location) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void flush() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
      throw new UnsupportedOperationException();
    }
    @Override
    public void setLocation(
        Location location,
        Iterable<? extends File> path) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<? extends File> getLocation(Location location) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int isSupportedOption(String option) {
      throw new UnsupportedOperationException();
    }
  }

  private static class FakeJavaFileObject extends FakeFileObject implements JavaFileObject {

    private final Kind kind;

    private FakeJavaFileObject(String name, Kind kind) {
      super(name);
      this.kind = kind;
    }

    @Override
    public Kind getKind() {
      return kind;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NestingKind getNestingKind() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Modifier getAccessLevel() {
      throw new UnsupportedOperationException();
    }
  }

  private static class FakeFileObject implements FileObject {

    protected final String name;

    private FakeFileObject(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public URI toUri() {
      try {
        return new URI(getJarPath(name));
      } catch (URISyntaxException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public InputStream openInputStream() throws IOException {
      return null;
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
      return null;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      return null;
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Writer openWriter() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getLastModified() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete() {
      throw new UnsupportedOperationException();
    }
  }
}
