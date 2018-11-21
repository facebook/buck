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

import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class PackageImplicitIncludesFinderTest {

  @Test
  public void parsesBasicConfiguration() {
    ImplicitInclude include1 =
        ImplicitInclude.fromConfigurationString("//:include_1.bzl::get_name");
    ImplicitInclude include2 =
        ImplicitInclude.fromConfigurationString("//:include_2.bzl::get_name");

    ImmutableMap<String, ImplicitInclude> config =
        ImmutableMap.of(
            "foo/bar/baz1", include1,
            "foo/bar", include2);

    PackageImplicitIncludesFinder finder = PackageImplicitIncludesFinder.fromConfiguration(config);

    Assert.assertEquals(
        finder.findIncludeForBuildFile(Paths.get("foo", "bar", "baz1", "subbaz")),
        Optional.of(include1));
    Assert.assertEquals(
        finder.findIncludeForBuildFile(Paths.get("foo", "bar", "baz1")), Optional.of(include1));
    Assert.assertEquals(
        finder.findIncludeForBuildFile(Paths.get("foo", "bar", "baz2")), Optional.of(include2));
    Assert.assertEquals(
        finder.findIncludeForBuildFile(Paths.get("foo", "bar")), Optional.of(include2));
    Assert.assertEquals(finder.findIncludeForBuildFile(Paths.get("foo")), Optional.empty());
    Assert.assertEquals(finder.findIncludeForBuildFile(Paths.get("BUCK")), Optional.empty());
  }

  @Test
  public void handlesRootConfigs() {
    ImmutableMap<String, ImplicitInclude> config =
        ImmutableMap.of("", ImplicitInclude.fromConfigurationString("//:include.bzl::get_name"));

    PackageImplicitIncludesFinder finder = PackageImplicitIncludesFinder.fromConfiguration(config);

    Optional<ImplicitInclude> expected =
        Optional.of(ImplicitInclude.fromConfigurationString("//:include.bzl::get_name"));

    Assert.assertEquals(expected, finder.findIncludeForBuildFile(Paths.get("foo", "bar", "baz")));
    Assert.assertEquals(expected, finder.findIncludeForBuildFile(Paths.get("")));
  }

  @Test
  public void handlesSkippingALevel() {
    ImmutableMap<String, ImplicitInclude> config =
        ImmutableMap.of(
            "foo", ImplicitInclude.fromConfigurationString("//:include_1.bzl::get_name"),
            "foo/bar/baz", ImplicitInclude.fromConfigurationString("//:include_2.bzl::get_name"));

    Optional<ImplicitInclude> expected =
        Optional.of(ImplicitInclude.fromConfigurationString("//:include_1.bzl::get_name"));

    PackageImplicitIncludesFinder finder = PackageImplicitIncludesFinder.fromConfiguration(config);

    Assert.assertEquals(expected, finder.findIncludeForBuildFile(Paths.get("foo", "bar")));
  }
}
