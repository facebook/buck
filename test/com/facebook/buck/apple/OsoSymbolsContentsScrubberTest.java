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

package com.facebook.buck.apple;

import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cxx.toolchain.objectfile.OsoSymbolsContentsScrubber;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Test suite for OsoSymbolsContentsScrubber. */
public class OsoSymbolsContentsScrubberTest {
  @Before
  public void setUp() {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
  }

  @Test
  public void testOsoPrefixForEmptyMap() {
    ImmutableMap<Path, Path> emptyMap = ImmutableMap.of();
    Optional<String> maybeOsoPrefix =
        OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(emptyMap);
    assertFalse(maybeOsoPrefix.isPresent());
  }

  @Test
  public void testOsoPrefixForSinglePath() {
    Path cellRoot = Paths.get("/path/to/cell");
    ImmutableMap<Path, Path> singlePathMap = ImmutableMap.of(cellRoot, Paths.get(""));
    Optional<String> maybeOsoPrefix =
        OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(singlePathMap);

    assertTrue(maybeOsoPrefix.isPresent());
    assertThat(maybeOsoPrefix.get(), equalTo("/path/to/cell/")); // NB: trailing slash
  }

  @Test
  public void testOsoPrefixForMultiplePathsCommonDir() {
    Path aCellRoot = Paths.get("/path/to/cell_a");
    Path bCellRoot = Paths.get("/path/to/cell_a/cell_b");
    ImmutableMap<Path, Path> singlePathMap =
        ImmutableMap.of(aCellRoot, Paths.get(""), bCellRoot, Paths.get("cell_b"));
    Optional<String> maybeOsoPrefix =
        OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(singlePathMap);

    assertTrue(maybeOsoPrefix.isPresent());
    assertThat(maybeOsoPrefix.get(), equalTo("/path/to/cell_a/")); // NB: trailing slash
  }

  @Test
  public void testOsoPrefixForMultiplePathsCommonPrefix() {
    Path aCellRoot = Paths.get("/path/to/repo/cell_a");
    Path bCellRoot = Paths.get("/path/to/repo/cell_b");
    ImmutableMap<Path, Path> singlePathMap =
        ImmutableMap.of(aCellRoot, Paths.get(""), bCellRoot, Paths.get("../cell_b"));
    Optional<String> maybeOsoPrefix =
        OsoSymbolsContentsScrubber.computeOsoPrefixForCellRootMap(singlePathMap);

    assertTrue(maybeOsoPrefix.isPresent());
    assertThat(maybeOsoPrefix.get(), equalTo("/path/to/repo/")); // NB: trailing slash
  }
}
