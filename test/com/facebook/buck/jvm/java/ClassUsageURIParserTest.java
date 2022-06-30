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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Test {@link com.facebook.buck.jvm.java.ClassUsageURIParser} */
public class ClassUsageURIParserTest {

  private static final String[] FILE_NAMES = {
    "A", "B", "C", "D", "E", "F", "NonJava", "OTHER", "SOURCE", "HTML"
  };
  private static final String SINGLE_FILE_NAME = "C.java";
  private static final Path SINGLE_FILE_PATH = Paths.get(SINGLE_FILE_NAME);
  private static final String SINGLE_NON_JAVA_FILE_NAME = "NonJava.json";
  private static final String TEST_JAR_URI =
      Platform.detect() == Platform.WINDOWS ? "/C:/test.jar" : "/test.jar";
  private static final Path TEST_JAR_PATH = Paths.get(URI.create("file://" + TEST_JAR_URI));

  private ClassUsageURIParser parser;

  @Before
  public void setUp() {
    parser = new ClassUsageURIParser();
  }

  @Test
  public void testRecordSimpleURI() {
    parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, SINGLE_FILE_NAME));
    assertClassUsagesRecordedExactly(TEST_JAR_PATH, SINGLE_FILE_NAME);
  }

  @Test
  public void testIncrementCountWhenRecordSameURI() {
    parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, SINGLE_FILE_NAME));
    parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, SINGLE_FILE_NAME));
    assertClassUsageRecordedWithCount(TEST_JAR_PATH, SINGLE_FILE_NAME, 2);
  }

  @Test
  public void testRecordNonJavaURI() {
    parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, SINGLE_NON_JAVA_FILE_NAME));
    assertClassUsagesRecordedExactly(TEST_JAR_PATH, SINGLE_NON_JAVA_FILE_NAME);
  }

  @Test
  public void testRecordMultipleURI() {
    for (String fileName : FILE_NAMES) {
      parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, fileName));
    }
    assertClassUsagesRecordedExactly(TEST_JAR_PATH, FILE_NAMES);
  }

  @Test
  public void testRecordURIWithSpecialSymbol() {
    String uriWithSpecialSymbol =
        Platform.detect() == Platform.WINDOWS
            ? "file:///C:/dummyDir/dummyTarget%23class-abi/dummyTarget-abi.jar"
            : "file:///dummyDir/dummyTarget%23class-abi/dummyTarget-abi.jar";
    parser.parseAndRecordURI(URI.create("jar:" + uriWithSpecialSymbol + "!/" + SINGLE_FILE_NAME));
    assertClassUsagesRecordedExactly(Paths.get(URI.create(uriWithSpecialSymbol)), SINGLE_FILE_NAME);
  }

  @Test
  public void testIgnoreEmptyURI() {
    parser.parseAndRecordURI(URI.create(""));
    assertNoClassUsageRecorded();
  }

  @Test
  public void testIgnoreNonJarURI() {
    parser.parseAndRecordURI(
        URI.create(String.format("jrt:file://%s!/%s", TEST_JAR_URI, SINGLE_FILE_PATH)));
    assertNoClassUsageRecorded();
  }

  @Test
  public void testIgnoreLocalClassURI() {
    parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, "Foo$3LocalFoo.class"));
    assertNoClassUsageRecorded();
  }

  @Test
  public void testIgnoreAnonymousClassURI() {
    parser.parseAndRecordURI(getJarURI(TEST_JAR_URI, "Foo$3.class"));
    assertNoClassUsageRecorded();
  }

  @Test
  public void testThrowInvalidURI() {
    // Don't have `!/`
    assertThrows(
        IllegalStateException.class,
        () -> {
          parser.parseAndRecordURI(
              URI.create(String.format("jar:file://%s!!!%s", TEST_JAR_URI, SINGLE_FILE_PATH)));
        });
    // 2nd schema is not file or jimfs
    assertThrows(
        IllegalStateException.class,
        () -> {
          parser.parseAndRecordURI(
              URI.create(String.format("jar:blah://%s!/%s", TEST_JAR_URI, SINGLE_FILE_PATH)));
        });
  }

  private URI getJarURI(String jarUri, String fileName) {
    return URI.create(String.format("jar:file://%s!/%s", jarUri, Paths.get(fileName)));
  }

  private void assertClassUsageRecordedWithCount(Path jarPath, String fileName, int count) {
    ImmutableMap<Path, Map<Path, Integer>> classUsageMap = parser.getClassUsageMap();
    assertEquals(Integer.valueOf(count), classUsageMap.get(jarPath).get(Paths.get(fileName)));
  }

  private void assertClassUsagesRecordedExactly(Path jarPath, String... files) {
    ImmutableMap<Path, Map<Path, Integer>> classUsageMap = parser.getClassUsageMap();
    assertTrue(
        classUsageMap + "does not contain key" + jarPath, classUsageMap.containsKey(jarPath));
    Set<Path> paths = classUsageMap.get(jarPath).keySet();
    assertEquals(files.length, paths.size());

    for (String file : files) {
      assertTrue(paths.contains(Paths.get(file)));
    }
  }

  private void assertNoClassUsageRecorded() {
    assertEquals(0, parser.getClassUsageMap().size());
  }
}
