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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.features.project.intellij.lang.java.ParsingJavaPackageFinder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class ParsingJavaPackageFinderTest {

  private Path matchPath;
  private Path mismatchPath;
  private Path placeholderPath;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private JavaPackageFinder dummyPackageFinder;
  private JavaFileParser javaFileParser;

  @Before
  public void setUp() {
    matchPath = Paths.get("case1/org/test/package1/Match.java");
    mismatchPath = Paths.get("case1/org/test/package2/Mismatch.java");
    placeholderPath = Paths.get("case3/com/test/placeholder");

    fakeProjectFilesystem =
        new FakeProjectFilesystem(
            FakeClock.doNotCare(),
            Paths.get(".").toAbsolutePath(),
            ImmutableSet.of(matchPath, mismatchPath, placeholderPath));
    fakeProjectFilesystem.writeContentsToPath(
        "package org.test.package1; \n class Match {} \n", matchPath);

    fakeProjectFilesystem.writeContentsToPath(
        "package org.test.\nhaha; \n class Mismatch {} \n", mismatchPath);

    dummyPackageFinder =
        new JavaPackageFinder() {
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

    javaFileParser =
        JavaFileParser.createJavaFileParser(JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS);
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
  public void testSuccessfullyGetSourceRootFromSource() {
    Path somePath = Paths.get("a/b/c/com/foo/bar/Baz.java");
    fakeProjectFilesystem.writeContentsToPath("package com.foo.bar;\nclass Baz {}", somePath);
    assertEquals(
        new ParsingJavaPackageFinder.PackagePathResolver(javaFileParser, fakeProjectFilesystem)
            .getSourceRootFromSource(somePath),
        Optional.of(Paths.get("a", "b", "c")));
  }

  @Test
  public void testGetSourceRootFromSourceWithMismatchedDirectories() {
    Path somePath = Paths.get("a/b/c/com/foo/boink/Baz.java");
    fakeProjectFilesystem.writeContentsToPath("package com.foo.bar;\nclass Baz {}", somePath);
    assertEquals(
        new ParsingJavaPackageFinder.PackagePathResolver(javaFileParser, fakeProjectFilesystem)
            .getSourceRootFromSource(somePath),
        Optional.empty());
  }

  @Test
  public void testMisleadingCommentsWithPackageStatement() {
    Path misleadingPath = Paths.get("case1/org/test/package1/Foo.java");
    fakeProjectFilesystem.writeContentsToPath(
        "/**\n"
            + "package misleading;\n"
            + "import haha;\n"
            + "*/\n"
            + "package org.is.correct;\n"
            + "\n"
            + "class Foo{}",
        misleadingPath);
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            fakeProjectFilesystem,
            ImmutableSortedSet.of(misleadingPath),
            dummyPackageFinder);
    assertEquals("org.is.correct", parsingJavaPackageFinder.findJavaPackage(misleadingPath));
  }

  @Test
  public void testMisleadingCommentsWithoutPackageGoesToFallback() {
    Path misleadingPath = Paths.get("case1/org/test/package1/Foo.java");
    fakeProjectFilesystem.writeContentsToPath(
        "/**\n" + "package misleading;\n" + "import haha;\n" + "*/\n" + "class Foo{}",
        misleadingPath);
    JavaPackageFinder parsingJavaPackageFinder =
        ParsingJavaPackageFinder.preparse(
            javaFileParser,
            fakeProjectFilesystem,
            ImmutableSortedSet.of(misleadingPath),
            dummyPackageFinder);
    assertEquals("dummy", parsingJavaPackageFinder.findJavaPackage(misleadingPath));
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
        "org.test", parsingJavaPackageFinder.findJavaPackage(Paths.get("case1/org/test/notfound")));
    assertEquals("com", parsingJavaPackageFinder.findJavaPackage(Paths.get("case1/com/notfound")));
  }
}
