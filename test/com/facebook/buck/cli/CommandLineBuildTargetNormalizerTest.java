/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CommandLineBuildTargetNormalizerTest {

  @Test
  public void testNormalize() {
    assertEquals("//src/com/facebook/orca:orca", normalize("src/com/facebook/orca"));
    assertEquals("//src/com/facebook/orca:orca", normalize("//src/com/facebook/orca"));
    assertEquals("//src/com/facebook/orca:orca", normalize("src/com/facebook/orca:orca"));
    assertEquals("//src/com/facebook/orca:orca", normalize("//src/com/facebook/orca:orca"));

    assertEquals("//src/com/facebook/orca:orca", normalize("src/com/facebook/orca/"));
    assertEquals("//src/com/facebook/orca:orca", normalize("//src/com/facebook/orca/"));

    assertEquals("//src/com/facebook/orca:messenger", normalize("src/com/facebook/orca:messenger"));
    assertEquals(
        "//src/com/facebook/orca:messenger", normalize("//src/com/facebook/orca:messenger"));

    // Because tab-completing a directory name often includes the trailing slash, we want to support
    // the user tab-completing, then typing the colon, followed by the short name.
    assertEquals(
        "Slash before colon should be stripped",
        "//src/com/facebook/orca:messenger",
        normalize("src/com/facebook/orca/:messenger"));

    // Assert the cell prefix normalizes
    assertEquals("other//src/com/facebook/orca:orca", normalize("other//src/com/facebook/orca"));
    assertEquals(
        "@other//src/com/facebook/orca:messenger",
        normalize("@other//src/com/facebook/orca:messenger"));
  }

  @Test(expected = NullPointerException.class)
  public void testNormalizeThrows() {
    normalize(null);
  }

  @Test
  public void testNormalizeTargetsAtRoot() {
    assertEquals("//:wakizashi", normalize("//:wakizashi"));
    assertEquals("//:wakizashi", normalize(":wakizashi"));
  }

  private String normalize(String buildTargetFromCommandLine) {
    return CommandLineBuildTargetNormalizer.normalizeBuildTargetIdentifier(
        buildTargetFromCommandLine);
  }
}
