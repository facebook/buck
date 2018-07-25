/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.runner;

import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class FileClassPathRunnerTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private String oldTestRunnerClassesPropertyValue;
  private String oldClassPathFilePropertyValue;

  @Before
  public void setUp() {
    oldTestRunnerClassesPropertyValue =
        System.getProperty(FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY);
    oldClassPathFilePropertyValue = System.getProperty(FileClassPathRunner.CLASSPATH_FILE_PROPERTY);
    System.clearProperty(FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY);
    System.clearProperty(FileClassPathRunner.CLASSPATH_FILE_PROPERTY);
  }

  @After
  public void tearDown() {
    if (oldTestRunnerClassesPropertyValue != null) {
      System.setProperty(
          FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY, oldTestRunnerClassesPropertyValue);
    }
    if (oldClassPathFilePropertyValue != null) {
      System.setProperty(
          FileClassPathRunner.CLASSPATH_FILE_PROPERTY, oldClassPathFilePropertyValue);
    }
  }

  @Test
  public void mainThrowsIfNoTestRunnerProperty() throws IOException, ReflectiveOperationException {
    thrown.expect(IllegalArgumentException.class);
    FileClassPathRunner.main(new String[] {"one"});
  }

  @Test
  public void getClassPathWithoutClassPathFile() throws IOException {
    Path testRunnerPath = temporaryPaths.newFolder("testrunner");
    System.setProperty(FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY, testRunnerPath.toString());
    final String expectedClassPathProperty = testRunnerPath.toString();
    final URL[] expectedResult = new URL[] {testRunnerPath.toUri().toURL()};

    StringBuilder classPathProperty = new StringBuilder();
    URL[] result = FileClassPathRunner.getClassPath(classPathProperty);
    Assert.assertEquals(expectedClassPathProperty, classPathProperty.toString());
    Assert.assertArrayEquals(expectedResult, result);
  }

  @Test
  public void getClassPathWithExistingClassPathFile() throws IOException {
    Path classPathFile = whenClassPathFileExists();
    System.setProperty(
        FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY,
        temporaryPaths.newFolder("testrunner").toString());
    System.setProperty(FileClassPathRunner.CLASSPATH_FILE_PROPERTY, classPathFile.toString());

    Path[] expectedEntries = expectedClassPathEntries();
    final String expectedClassPathProperty =
        String.format(
            "%s:%s:%s",
            temporaryPaths.getRoot().resolve("testrunner"), expectedEntries[0], expectedEntries[1]);
    final URL[] expectedResult =
        new URL[] {
          temporaryPaths.getRoot().resolve("testrunner").toUri().toURL(),
          expectedEntries[0].toUri().toURL(),
          expectedEntries[1].toUri().toURL()
        };

    StringBuilder classPathProperty = new StringBuilder();
    URL[] result = FileClassPathRunner.getClassPath(classPathProperty);
    Assert.assertEquals(expectedClassPathProperty, classPathProperty.toString());
    Assert.assertArrayEquals(expectedResult, result);
  }

  @Test
  public void getClassPathWithNonExistingClassPathFile() throws IOException {
    thrown.expect(NoSuchFileException.class);
    System.setProperty(
        FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY,
        temporaryPaths.newFolder("testrunner").toString());
    System.setProperty(FileClassPathRunner.CLASSPATH_FILE_PROPERTY, "/path/doesnt/exist");

    StringBuilder classPathProperty = new StringBuilder();
    FileClassPathRunner.getClassPath(classPathProperty);
  }

  @Test
  public void getTestClassPathWhenFileDoesNotExist() throws IOException {
    thrown.expect(NoSuchFileException.class);
    FileClassPathRunner.getTestClassPath(Paths.get("/tmp/doesnotexist"));
  }

  @Test
  public void getTestClassPathWhenSomeEntriesDoNotExist() throws IOException {
    Path classPathFile =
        whenClassPathFileExistsWithFakeEntries(
            temporaryPaths.getRoot().resolve("lib3.jar").toString());

    final List<Path> expectedTestClassPath = Arrays.asList(expectedClassPathEntries());
    List<Path> testClassPath = FileClassPathRunner.getTestClassPath(classPathFile);
    Assert.assertEquals(expectedTestClassPath, testClassPath);
  }

  private Path whenClassPathFileExists() throws IOException {
    return whenClassPathFileExistsWithFakeEntries();
  }

  private Path[] expectedClassPathEntries() {
    return new Path[] {
      temporaryPaths.getRoot().resolve("lib1.jar"), temporaryPaths.getRoot().resolve("lib2.jar")
    };
  }

  private Path whenClassPathFileExistsWithFakeEntries(String... fakeClassPathEntries)
      throws IOException {
    List<String> classPathEntries = new ArrayList<>();
    classPathEntries.add(temporaryPaths.newFile("lib1.jar").toString());
    classPathEntries.add(temporaryPaths.newFile("lib2.jar").toString());
    Path classpathFile = temporaryPaths.newFile("classpathFile");
    if (fakeClassPathEntries != null) {
      classPathEntries.addAll(Arrays.asList(fakeClassPathEntries));
    }
    Files.write(classpathFile, classPathEntries);
    return classpathFile;
  }

  @Test
  public void constructArgsWhenOneArg() {
    final String[] expectedArgs = {};
    String[] args = FileClassPathRunner.constructArgs(new String[] {"one"});
    Assert.assertArrayEquals(expectedArgs, args);
  }

  @Test
  public void constructArgsWhenManyArgs() {
    final String[] expectedArgs = {"two"};
    String[] args = FileClassPathRunner.constructArgs(new String[] {"one", "two"});
    Assert.assertArrayEquals(expectedArgs, args);
  }

  @Test
  public void getPlatformClassLoaderOnJava8() {
    Assume.assumeTrue(isJava8());
    thrown.expect(AssertionError.class);
    FileClassPathRunner.findPlatformClassLoader();
  }

  @Test
  public void getPlatformClassLoaderOnJava9Plus() {
    Assume.assumeTrue(isJava9Plus());
    ClassLoader loader = FileClassPathRunner.findPlatformClassLoader();
    Assert.assertNotNull(loader);
  }

  private static boolean isJava8() {
    String javaVersion = System.getProperty("java.version");
    return "1.8".equals(javaVersion) || javaVersion.startsWith("1.8.");
  }

  private static boolean isJava9Plus() {
    String javaVersion = System.getProperty("java.version");
    return javaVersion.startsWith("9.") || javaVersion.startsWith("10.");
  }
}
