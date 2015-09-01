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

package com.facebook.buck.java.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.JavaCompilationConstants;
import com.facebook.buck.java.JavaFileParser;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.FakeClock;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ParsingJavaPackageFinderTest {

  private Path matchPath;
  private Path mismatchPath;
  private Path placeholderPath;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private JavaPackageFinder dummyPackageFinder;
  private JavaFileParser javaFileParser;

  @Before
  public void setUp() throws IOException {
    matchPath = Paths.get("case1/org/test/package1/Match.java");
    mismatchPath = Paths.get("case1/org/test/package2/Mismatch.java");
    placeholderPath = Paths.get("case3/com/test/placeholder");

    fakeProjectFilesystem = new FakeProjectFilesystem(
        new FakeClock(0),
        Paths.get(".").toFile(),
        ImmutableSet.of(
            matchPath,
            mismatchPath,
            placeholderPath
        ));
    fakeProjectFilesystem.writeContentsToPath(
        "package org.test.package1; \n class Match {} \n",
        matchPath);

    fakeProjectFilesystem.writeContentsToPath(
        "package org.test.\nhaha; \n class Mismatch {} \n",
        mismatchPath);

    dummyPackageFinder = new JavaPackageFinder() {
      @Override
      public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
        return Paths.get("dummy");
      }

      @Override
      public String findJavaPackage(Path pathRelativeToProjectRoot) {
        return "dummy";
      }

      @Override
      public String findJavaPackage(BuildTarget buildTarget) {
        return "dummy";
      }
    };

    javaFileParser = JavaFileParser.createJavaFileParser(
        JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS);
  }

  @Test
  public void testFindsPackageFromFile() {
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            fakeProjectFilesystem,
            ImmutableSortedSet.of(matchPath),
            dummyPackageFinder);

    assertEquals("org.test.package1", parsingJavaPackageFinder.findJavaPackage(matchPath));
  }

  @Test
  public void testFallBackToDefaultFinder() {
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            fakeProjectFilesystem,
            ImmutableSortedSet.of(placeholderPath),
            dummyPackageFinder);

    assertEquals("dummy", parsingJavaPackageFinder.findJavaPackage(placeholderPath));
  }

  @Test
  public void testFileContentsOverConvention() {
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            fakeProjectFilesystem,
            ImmutableSortedSet.of(mismatchPath),
            dummyPackageFinder);

    assertEquals("org.test.haha", parsingJavaPackageFinder.findJavaPackage(mismatchPath));
  }

  @Test
  public void testCaching() {
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            fakeProjectFilesystem,
            ImmutableSortedSet.of(matchPath, mismatchPath),
            dummyPackageFinder);

    assertEquals("org.test.package1", parsingJavaPackageFinder.findJavaPackage(matchPath));
    assertEquals("org.test.haha", parsingJavaPackageFinder.findJavaPackage(mismatchPath));
    assertEquals(
        "org.test.package3",
        parsingJavaPackageFinder.findJavaPackage(Paths.get("case1/org/test/package3/notfound")));
    assertEquals(
        "org.test",
        parsingJavaPackageFinder.findJavaPackage(Paths.get("case1/org/test/notfound")));
    assertEquals(
        "com",
        parsingJavaPackageFinder.findJavaPackage(Paths.get("case1/com/notfound")));
  }
}

