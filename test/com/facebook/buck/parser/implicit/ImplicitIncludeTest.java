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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ImplicitIncludeTest {
  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void returnsProperLoadPath() {
    Assert.assertEquals(
        "//:foo.bzl",
        ImplicitInclude.of("//:foo.bzl", ImmutableMap.of("get_name", "get_name"))
            .getLoadPath()
            .getImportString());

    Assert.assertEquals(
        "//foo/bar/baz:include.bzl",
        ImplicitInclude.of("//foo/bar/baz:include.bzl", ImmutableMap.of("get_name", "get_name"))
            .getLoadPath()
            .getImportString());

    Assert.assertEquals(
        "@cell//foo/bar/baz:include.bzl",
        ImplicitInclude.of(
                "@cell//foo/bar/baz:include.bzl", ImmutableMap.of("get_name", "get_name"))
            .getLoadPath()
            .getImportString());
  }

  @Test
  public void failsOnInvalidLabel() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Invalid implicit label provided");

    ImplicitInclude.fromConfigurationString("cell//foo:bar.bzl::symbol1");
  }

  @Test
  public void doesNotAllowRelativeIncludes() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(
        "specifies a non-absolute load path. It must be relative to the project root, or to another cell's root");

    ImplicitInclude.fromConfigurationString(":bar.bzl::symbol1");
  }

  @Test
  public void doesNotAllowRawPaths() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("does not specify a file to load in its label. Does it contain a ':'?");

    ImplicitInclude.fromConfigurationString("//foo/bar.bzl::symbol1");
  }

  @Test
  public void failsOnMissingSymbols() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("did not list any symbols");

    ImplicitInclude.fromConfigurationString("//foo:bar.bzl");
  }

  @Test
  public void failsOnEmptySymbols() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("specifies an empty path/symbols");

    ImplicitInclude.fromConfigurationString("//foo:bar.bzl::::symbol2");
  }

  @Test
  public void failsOnEmptySymbolWithAliasDelimiter() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("specifies an empty symbol");

    ImplicitInclude.fromConfigurationString("//foo:bar.bzl::=");
  }

  @Test
  public void failsOnEmptyAlias() {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("specifies an empty symbol alias");

    ImplicitInclude.fromConfigurationString("//foo:bar.bzl::=symbol2");
  }

  @Test
  public void parsesConfigurationStrings() {
    ImplicitInclude expected =
        ImplicitInclude.of(
            "//foo:bar.bzl",
            ImmutableMap.of(
                "symbol1", "symbol1",
                "symbol2", "symbol2",
                "symbol_alias", "symbol3"));
    Assert.assertEquals(
        expected,
        ImplicitInclude.fromConfigurationString(
            "//foo:bar.bzl::symbol1::symbol2::symbol_alias=symbol3"));
  }
}
