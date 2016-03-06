/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.go;

import com.facebook.buck.io.MorePaths;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.hamcrest.Matchers;
import org.junit.Test;
import static org.junit.Assert.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class GoDescriptorsTest {
  private ImmutableMap<String, String> getPackageImportMap(
      String basePackage, Iterable<String> packages) {
    return ImmutableMap.copyOf(FluentIterable.from(
        GoDescriptors.getPackageImportMap(
            Paths.get(basePackage),
            FluentIterable.from(packages).transform(MorePaths.TO_PATH)).entrySet())
        .transform(new Function<Map.Entry<Path, Path>, Map.Entry<String, String>>() {
          @Override
          public Map.Entry<String, String> apply(Map.Entry<Path, Path> input) {
            return Maps.immutableEntry(
                MorePaths.pathWithUnixSeparators(input.getKey()),
                MorePaths.pathWithUnixSeparators(input.getValue()));
          }
        }));
  }

  @Test
  public void testImportMapEmpty() {
    assertThat(
        getPackageImportMap(
            "foo/bar/baz",
            ImmutableList.of(
                "foo/bar",
                "bar",
                "foo/bar/baz/waffle"
            )),
        Matchers.anEmptyMap());
  }

  @Test
  public void testImportMapRoot() {
    assertThat(
        getPackageImportMap(
            "foo/bar/baz",
            ImmutableList.of(
                "foo/bar",
                "bar",
                "foo/bar/baz/waffle",
                "foo/vendor/hello/world"
            )),
        Matchers.equalTo(ImmutableMap.of(
            "hello/world", "foo/vendor/hello/world")));
  }

  @Test
  public void testImportMapNonRoot() {
    assertThat(
        getPackageImportMap(
            "foo/bar/baz",
            ImmutableList.of(
                "foo/bar",
                "bar",
                "foo/bar/baz/waffle",
                "foo/bar/vendor/hello/world"
            )),
        Matchers.equalTo(ImmutableMap.of(
            "hello/world", "foo/bar/vendor/hello/world")));
  }

  @Test
  public void testImportMapLongestWins() {
    assertThat(
        getPackageImportMap(
            "foo/bar/baz",
            ImmutableList.of(
                "foo/bar",
                "bar",
                "foo/bar/baz/waffle",
                "vendor/hello/world",
                "foo/bar/vendor/hello/world"
            )),
        Matchers.equalTo(ImmutableMap.of(
            "hello/world", "foo/bar/vendor/hello/world")));
  }
}
