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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

public class DebugPathSanitizerTest {

  DebugPathSanitizer debugPathSanitizer;

  @Before
  public void setUp() {
    debugPathSanitizer = new DebugPathSanitizer(
        40,
        '/',
        Paths.get("."),
        ImmutableBiMap.of(
            Paths.get("/some/absolute/path"),
            Paths.get("SYMBOLIC_NAME"),
            Paths.get("/another/path/with/subdirectories"),
            Paths.get("OTHER_NAME_WITH_SUFFIX"),
            Paths.get("/another/path"),
            Paths.get("OTHER_NAME")));
  }

  @Test
  public void sanitizeWithoutAnyMatches() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "an arbitrary string with no match"),
        equalTo("an arbitrary string with no match"));
  }

  @Test
  public void sanitizeProjectRoot() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "a string that mentions the /project/root somewhere"),
        equalTo("a string that mentions the . somewhere"));
  }

  @Test
  public void sanitizeOtherDirectories() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "-I/some/absolute/path/dir -I/another/path"),
        equalTo("-ISYMBOLIC_NAME/dir -IOTHER_NAME"));
  }

  @Test
  public void sanitizeDirectoriesThatArePrefixOfOtherDirectories() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "-I/another/path/with/subdirectories/something"),
        equalTo("-IOTHER_NAME_WITH_SUFFIX/something"));
  }

  @Test
  public void restoreWithoutAnyMatches() {
    assertThat(
        debugPathSanitizer.restore(
            Optional.of(Paths.get("/project/root")),
            "an arbitrary string with no match"),
        equalTo("an arbitrary string with no match"));
  }

  @Test
  public void restoreProjectRoot() {
    assertThat(
        debugPathSanitizer.restore(
            Optional.of(Paths.get("/project/root")),
            "a string that mentions the ./////////////////////////////////////// somewhere"),
        equalTo("a string that mentions the /project/root somewhere"));
  }

  @Test
  public void restoreOtherDirectories() {
    assertThat(
        debugPathSanitizer.restore(
            Optional.of(Paths.get("/project/root")),
            "-ISYMBOLIC_NAME////////////////////////////dir " +
                "-IOTHER_NAME//////////////////////////////"),
        equalTo("-I/some/absolute/path/dir -I/another/path"));
  }

  @Test
  public void restoreDirectoriesThatArePrefixOfOtherDirectories() {
    assertThat(
        debugPathSanitizer.restore(
            Optional.of(Paths.get("/project/root")),
            "-IOTHER_NAME_WITH_SUFFIX///////////////////something"),
        equalTo("-I/another/path/with/subdirectories/something"));
  }

  @Test
  public void restoreDoesNotTouchUnexpandedPaths() {
    assertThat(
        debugPathSanitizer.restore(
            Optional.of(Paths.get("/project/root")),
            ". -ISYMBOLIC_NAME/ OTHER_NAME"),
        equalTo(". -ISYMBOLIC_NAME/ OTHER_NAME"));
  }

}
