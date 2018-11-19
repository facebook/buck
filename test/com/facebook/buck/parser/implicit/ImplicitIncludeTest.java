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
package com.facebook.buck.parser.implicit;

import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ImplicitIncludeTest {
  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void returnsProperLoadPath() {
    Assert.assertEquals(
        "//:foo.bzl",
        ImplicitInclude.of(Paths.get("foo.bzl"), ImmutableMap.of("get_name", "get_name"))
            .getLoadPath()
            .getImportString());

    Assert.assertEquals(
        "//foo/bar/baz:include.bzl",
        ImplicitInclude.of(
                Paths.get("foo", "bar", "baz", "include.bzl"),
                ImmutableMap.of("get_name", "get_name"))
            .getLoadPath()
            .getImportString());
  }

  @Test
  public void failsOnMissingSymbols() {
    expected.expect(RuntimeException.class);
    expected.expectMessage("did not list any symbols");

    ImplicitInclude.fromConfigurationString("foo/bar.bzl");
  }

  @Test
  public void failsOnInvalidPath() {
    Assume.assumeTrue(Platform.detect() == Platform.WINDOWS);
    expected.expect(RuntimeException.class);
    expected.expectMessage("is not a valid path");

    ImplicitInclude.fromConfigurationString("\\C:\\path.bzl::symbol1");
  }

  @Test
  public void failsOnAbsolutePath() {
    expected.expect(RuntimeException.class);
    expected.expectMessage("may not be absolute");

    String absolutePath = Platform.detect() == Platform.WINDOWS ? "C:/foo/bar.bzl" : "/foo/bar.bzl";

    ImplicitInclude.fromConfigurationString(absolutePath + "::symbol1");
  }

  @Test
  public void failsOnEmptySymbols() {
    expected.expect(RuntimeException.class);
    expected.expectMessage("specifies an empty path/symbols");

    ImplicitInclude.fromConfigurationString("foo/bar.bzl::::symbol2");
  }

  @Test
  public void parsesConfigurationStrings() {
    ImplicitInclude expected =
        ImplicitInclude.of(
            Paths.get("foo", "bar.bzl"),
            ImmutableMap.of(
                "symbol1", "symbol1",
                "symbol2", "symbol2"));
    Assert.assertEquals(
        expected, ImplicitInclude.fromConfigurationString("foo/bar.bzl::symbol1::symbol2"));
  }
}
