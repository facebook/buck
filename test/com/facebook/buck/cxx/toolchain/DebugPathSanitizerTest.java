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

package com.facebook.buck.cxx.toolchain;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableBiMap;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class DebugPathSanitizerTest {

  MungingDebugPathSanitizer debugPathSanitizer;

  @Before
  public void setUp() {
    debugPathSanitizer =
        new MungingDebugPathSanitizer(
            40,
            '/',
            Paths.get("."),
            ImmutableBiMap.of(
                Paths.get("/some/absolute/path"),
                "SYMBOLIC_NAME",
                Paths.get("/another/path/with/subdirectories"),
                "OTHER_NAME_WITH_SUFFIX",
                Paths.get("/another/path"),
                "OTHER_NAME"));
  }

  @Test
  public void sanitizeWithoutAnyMatches() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")), "an arbitrary string with no match"),
        equalTo("an arbitrary string with no match"));
  }

  @Test
  public void sanitizeProjectRoot() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "a string that mentions the " + Paths.get("/project/root somewhere")),
        equalTo("a string that mentions the . somewhere"));
  }

  @Test
  public void sanitizeOtherDirectories() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "-I" + Paths.get("/some/absolute/path/dir") + " -I" + Paths.get("/another/path")),
        equalTo("-I" + Paths.get("SYMBOLIC_NAME/dir") + " -IOTHER_NAME"));
  }

  @Test
  public void sanitizeDirectoriesThatArePrefixOfOtherDirectories() {
    assertThat(
        debugPathSanitizer.sanitize(
            Optional.of(Paths.get("/project/root")),
            "-I" + Paths.get("/another/path/with/subdirectories/something")),
        equalTo("-I" + Paths.get("OTHER_NAME_WITH_SUFFIX/something")));
  }
}
