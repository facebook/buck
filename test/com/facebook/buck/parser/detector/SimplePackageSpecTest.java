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

package com.facebook.buck.parser.detector;

import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import org.junit.Assert;
import org.junit.Test;

public class SimplePackageSpecTest {

  private static void assertMatches(String spec, String target) {
    Assert.assertTrue(
        SimplePackageSpec.parse(spec, TestCellNameResolver.forRoot("foo", "bar"))
            .matches(UnconfiguredBuildTargetParser.parse(target)));
  }

  private static void assertDoesNotMatch(String spec, String target) {
    Assert.assertFalse(
        SimplePackageSpec.parse(spec, TestCellNameResolver.forRoot("foo", "bar"))
            .matches(UnconfiguredBuildTargetParser.parse(target)));
  }

  @Test
  public void root() {
    assertMatches("//...", "//:a");
    assertMatches("//...", "//b:a");
    assertMatches("//...", "//c/b:a");
  }

  @Test
  public void rootWithCell() {
    assertMatches("foo//...", "foo//:a");
    assertMatches("foo//...", "foo//b:a");
    assertMatches("foo//...", "foo//c/b:a");
  }

  @Test
  public void prefix() {
    assertMatches("//bb/...", "//bb:a");
    assertMatches("//bb/...", "//bb/cc:a");
    assertMatches("foo//bb/...", "foo//bb:a");
    assertMatches("foo//bb/...", "foo//bb/cc:a");

    assertDoesNotMatch("foo//bb/...", "foo//b:c");
    assertDoesNotMatch("foo//bb/...", "foo//b/c:d");
  }

  @Test
  public void differentCell() {
    assertDoesNotMatch("foo//...", "bar//:a");
    assertDoesNotMatch("foo//...", "bar//b:a");
    assertDoesNotMatch("foo//b/...", "bar//b:a");
    assertDoesNotMatch("//...", "bar//:a");
    assertDoesNotMatch("//...", "bar//b:a");
    assertDoesNotMatch("//b/...", "bar//b:a");
    assertDoesNotMatch("foo//...", "//:a");
    assertDoesNotMatch("foo//...", "//b:a");
    assertDoesNotMatch("foo//b/...", "//b:a");
  }
}
