/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.CompilerOutputPaths.getKAPTDepFilePath;
import static com.facebook.buck.jvm.java.CompilerOutputPaths.getKotlinTempDepFilePath;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Test {@link KotlinClassUsageHelper} */
public class KotlinClassUsageHelperTest {

  public static final String FOO_TEST_FILE_NAME = "Foo.class";
  public static final String BAR_TEST_FILE_NAME = "Bar.class";
  private static final String TEST_JAR_URI =
      Platform.detect() == Platform.WINDOWS ? "/C:/test.jar" : "/test.jar";
  private static final Path TEST_JAR_PATH = Paths.get(URI.create("file://" + TEST_JAR_URI));
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private RelPath outputDir;

  @Before
  public void setUp() throws IOException {
    tmp.newFolder("dummyOutputDir");
    outputDir = RelPath.get("dummyOutputDir");
  }

  @Test
  public void testReadJsonDepFile() throws IOException {
    AbsPath kotlinTempDepPath =
        generateDummyKotlinTempFile(
            "dummy.json", ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 1)));

    assertEquals(
        KotlinClassUsageHelper.readJsonBasedClassUsageReport(kotlinTempDepPath.getPath()),
        ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 1)));
  }

  @Test
  public void testReadURIListDepFile() throws IOException {
    AbsPath kaptTempDepPath =
        generateDummyKaptTempFile(
            "dummy.txt",
            ImmutableList.of(
                getURI(TEST_JAR_URI, FOO_TEST_FILE_NAME),
                getURI(TEST_JAR_URI, FOO_TEST_FILE_NAME),
                getURI(TEST_JAR_URI, BAR_TEST_FILE_NAME)));

    assertEquals(
        KotlinClassUsageHelper.readUriBasedClassUsageFile(kaptTempDepPath.getPath()),
        ImmutableMap.of(
            TEST_JAR_PATH,
            Map.of(Paths.get(FOO_TEST_FILE_NAME), 2, Paths.get(BAR_TEST_FILE_NAME), 1)));
  }

  @Test
  public void testMergeSameEntry() {
    assertEquals(
        KotlinClassUsageHelper.merge(
            ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 1)),
            ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 1))),
        ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 2)));
  }

  @Test
  public void testMergeSameAndDifferentEntries() {
    assertEquals(
        KotlinClassUsageHelper.merge(
            ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 1)),
            ImmutableMap.of(
                TEST_JAR_PATH,
                Map.of(Paths.get(FOO_TEST_FILE_NAME), 1, Paths.get(BAR_TEST_FILE_NAME), 1))),
        ImmutableMap.of(
            TEST_JAR_PATH,
            Map.of(Paths.get(FOO_TEST_FILE_NAME), 2, Paths.get(BAR_TEST_FILE_NAME), 1)));
  }

  @Test
  public void testReadAllKotlinTempDepFiles() throws IOException {
    generateDummyKotlinTempFile(
        getKotlinTempDepFilePath(outputDir).toString(),
        ImmutableMap.of(TEST_JAR_PATH, Map.of(Paths.get(FOO_TEST_FILE_NAME), 1)));
    generateDummyKaptTempFile(
        getKAPTDepFilePath(outputDir).toString(),
        ImmutableList.of(
            getURI(TEST_JAR_URI, FOO_TEST_FILE_NAME),
            getURI(TEST_JAR_URI, FOO_TEST_FILE_NAME),
            getURI(TEST_JAR_URI, BAR_TEST_FILE_NAME)));

    assertEquals(
        KotlinClassUsageHelper.getClassUsageData(outputDir, tmp.getRoot()),
        ImmutableMap.of(
            TEST_JAR_PATH,
            Map.of(Paths.get(FOO_TEST_FILE_NAME), 3, Paths.get(BAR_TEST_FILE_NAME), 1)));
  }

  private AbsPath generateDummyKotlinTempFile(
      String fileName, ImmutableMap<Path, Map<Path, Integer>> dummyClassUsageJson)
      throws IOException {
    AbsPath kotlinTempFile = tmp.newFile(fileName);
    ObjectMappers.WRITER.writeValue(kotlinTempFile.toFile(), dummyClassUsageJson);
    return kotlinTempFile;
  }

  private AbsPath generateDummyKaptTempFile(String fileName, ImmutableList<String> uriList)
      throws IOException {
    AbsPath kaptTempDepPath = tmp.newFile(fileName);
    Files.write(kaptTempDepPath.getPath(), uriList);
    return kaptTempDepPath;
  }

  private String getURI(String jarUri, String fileName) {
    return String.format("jar:file://%s!/%s", jarUri, Paths.get(fileName));
  }
}
