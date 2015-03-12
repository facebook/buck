/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java.runner;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class FileClassPathRunnerTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void shouldNotTryAndReadFilesIfNoneAreFoundInUrlList() throws IOException {
    URL[] urls = new URL[] {new URL("file://foo/bar")};

    List<Path> classpathFiles = FileClassPathRunner.getClasspathFiles(urls);
    assertEquals(0, classpathFiles.size());
  }

  @Test
  public void shouldIdentifyFilesThatAreAnnotatedWithALeadingAtSign() throws IOException {
    URL[] toLoad = {
        new URL("file://foo/bar"),
        new URL("file:/opt/@./cake")};

    List<Path> classpathFiles = FileClassPathRunner.getClasspathFiles(toLoad);
    assertEquals(1, classpathFiles.size());
    assertEquals(Paths.get("./cake"), classpathFiles.get(0));
  }

  @Test
  public void shouldReadUrlsFromClasspathFile() throws IOException {
    Path urlsAreHere = tmp.newFile().toPath();
    Files.write(urlsAreHere,
        ImmutableList.of(
            "vegetables.jar",
            "cheese.jar"),
        UTF_8);

    List<URL> readUrls = FileClassPathRunner.readUrls(ImmutableList.of(urlsAreHere), false);

    assertEquals(
        ImmutableList.of(urlify("vegetables.jar"), urlify("cheese.jar")),
        readUrls);
  }

  @Test
  public void shouldAddReadUrlsToGivenUrlClassLoader()
      throws IOException, ReflectiveOperationException {
    Path urlsAreHere = tmp.newFile().toPath();
    Files.write(urlsAreHere,
        ImmutableList.of(
            "vegetables.jar",
            "cheese.jar"),
        UTF_8);

    URL url = new URL("file:@" + urlsAreHere.toAbsolutePath());

    try (URLClassLoader loader = new URLClassLoader(new URL[] { url })) {
      FileClassPathRunner.modifyClassLoader(loader, false);

      List<URL> allUrls = Arrays.asList(loader.getURLs());

      assertTrue(allUrls.contains(urlify("vegetables.jar")));
      assertTrue(allUrls.contains(urlify("cheese.jar")));
    }
  }

  @Test
  public void nullClassPathWithNoEntriesRemainsEmpty() {
    StringBuilder builder = new StringBuilder();
    FileClassPathRunner.constructNewClassPath(builder, null, ImmutableList.<String>of());
    assertEquals("", builder.toString());
  }

  @Test
  public void emptyClassPathWithNoEntriesRemainsEmpty() {
    StringBuilder builder = new StringBuilder();
    FileClassPathRunner.constructNewClassPath(builder, "", ImmutableList.<String>of());
    assertEquals("", builder.toString());
  }

  @Test
  public void nullClassPathAppendedWithOneEntryOnlyContainsEntry() {
    StringBuilder builder = new StringBuilder();
    FileClassPathRunner.constructNewClassPath(builder, null, ImmutableList.of("peas"));
    assertEquals("peas", builder.toString());
  }

  @Test
  public void nullClassPathAppendedWithMultipleEntriesAddsPathSeparator() {
    StringBuilder builder = new StringBuilder();
    FileClassPathRunner.constructNewClassPath(builder, null, ImmutableList.of("peas", "cheese"));
    assertEquals("peas" + File.pathSeparator + "cheese", builder.toString());
  }

  @Test
  public void existingClassPathIsSeparatedFromNewClassPathByPathSeparator() {
    StringBuilder builder = new StringBuilder();
    FileClassPathRunner.constructNewClassPath(builder, "cake", ImmutableList.of("peas", "cheese"));
    assertEquals(
        "cake" + File.pathSeparator + "peas" + File.pathSeparator + "cheese",
        builder.toString());
  }

  private URL urlify(String string) throws MalformedURLException {
    return Paths.get(string).toUri().toURL();
  }
}
