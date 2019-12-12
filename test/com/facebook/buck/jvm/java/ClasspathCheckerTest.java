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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import org.junit.Test;

public class ClasspathCheckerTest {
  @Test
  public void emptyClasspathIsNotValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath(""), is(false));
  }

  @Test
  public void classpathWithNonExistingDirIsNotValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("does-not-exist"), is(false));
  }

  @Test
  public void classpathWithExistingDirIsValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> dir.equals(Paths.get("exists")),
            file -> false,
            (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("exists"), is(true));
  }

  @Test
  public void classpathWithBothNonAndExistingDirIsValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> dir.equals(Paths.get("exists")),
            file -> false,
            (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("does-not-exist:exists"), is(true));
  }

  @Test
  public void classpathWithNonExistingJarIsNotValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("does-not-exist.jar"), is(false));
  }

  @Test
  public void classpathWithExistingJarIsValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> false,
            file -> file.equals(Paths.get("exists.jar")),
            (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("exists.jar"), is(true));
  }

  @Test
  public void classpathWithExistingZipIsValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> false,
            file -> file.equals(Paths.get("exists.zip")),
            (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("exists.zip"), is(true));
  }

  @Test
  public void classpathWithExistingNonJarOrZipIsNotValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> false,
            file -> file.equals(Paths.get("exists.notajar")),
            (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("exists.notajar"), is(false));
  }

  @Test
  public void classpathWithEmptyGlobIsNotValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());
    assertThat(classpathChecker.validateClasspath("*"), is(false));
  }

  @Test
  public void classpathWithJarGlobIsValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> false,
            file -> false,
            (path, glob) -> ImmutableSet.of(Paths.get("foo.jar")));
    assertThat(classpathChecker.validateClasspath("*"), is(true));
  }

  @Test
  public void classpathWithSubdirJarGlobIsValid() {
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/",
            ":",
            Paths::get,
            dir -> false,
            file -> false,
            (path, glob) -> ImmutableSet.of(Paths.get("foo/bar/foo.jar")));
    assertThat(classpathChecker.validateClasspath("foo/bar/*"), is(true));
  }
}
