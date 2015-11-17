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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleDescriptionsTest {

  @Test
  public void parseAppleHeadersForUseFromOtherTargetsFromSet() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "prefix/some_file.h", new FakeSourcePath("path/to/some_file.h"),
            "prefix/another_file.h", new FakeSourcePath("path/to/another_file.h"),
            "prefix/a_file.h", new FakeSourcePath("different/path/to/a_file.h"),
            "prefix/file.h", new FakeSourcePath("file.h")),
        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
            new SourcePathResolver(new BuildRuleResolver()).deprecatedPathFunction(),
            Paths.get("prefix"),
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.<SourcePath>of(
                    new FakeSourcePath("path/to/some_file.h"),
                    new FakeSourcePath("path/to/another_file.h"),
                    new FakeSourcePath("different/path/to/a_file.h"),
                    new FakeSourcePath("file.h")))));
  }

  @Test
  public void parseAppleHeadersForUseFromTheSameFromSet() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "some_file.h", new FakeSourcePath("path/to/some_file.h"),
            "another_file.h", new FakeSourcePath("path/to/another_file.h"),
            "a_file.h", new FakeSourcePath("different/path/to/a_file.h"),
            "file.h", new FakeSourcePath("file.h")),
        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
            new SourcePathResolver(new BuildRuleResolver()).deprecatedPathFunction(),
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.<SourcePath>of(
                    new FakeSourcePath("path/to/some_file.h"),
                    new FakeSourcePath("path/to/another_file.h"),
                    new FakeSourcePath("different/path/to/a_file.h"),
                    new FakeSourcePath("file.h")))));
  }

  @Test
  public void parseAppleHeadersForUseFromOtherTargetsFromMap() {
    ImmutableSortedMap<String, SourcePath> headerMap = ImmutableSortedMap.<String, SourcePath>of(
        "virtual/path.h", new FakeSourcePath("path/to/some_file.h"),
        "another/path.h", new FakeSourcePath("path/to/another_file.h"),
        "another/file.h", new FakeSourcePath("different/path/to/a_file.h"),
        "file.h", new FakeSourcePath("file.h"));
    assertEquals(
        headerMap,
        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
            new SourcePathResolver(new BuildRuleResolver()).deprecatedPathFunction(),
            Paths.get("prefix"),
            SourceList.ofNamedSources(headerMap)));
  }

  @Test
  public void parseAppleHeadersForUseFromTheSameTargetFromMap() {
    ImmutableSortedMap<String, SourcePath> headerMap = ImmutableSortedMap.<String, SourcePath>of(
        "virtual/path.h", new FakeSourcePath("path/to/some_file.h"),
        "another/path.h", new FakeSourcePath("path/to/another_file.h"),
        "another/file.h", new FakeSourcePath("different/path/to/a_file.h"),
        "file.h", new FakeSourcePath("file.h"));
    assertEquals(
        ImmutableMap.of(),
        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
            new SourcePathResolver(new BuildRuleResolver()).deprecatedPathFunction(),
            SourceList.ofNamedSources(headerMap)));
  }

  @Test
  public void convertToFlatCxxHeadersWithPrefix() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "prefix/some_file.h", new FakeSourcePath("path/to/some_file.h"),
            "prefix/another_file.h", new FakeSourcePath("path/to/another_file.h"),
            "prefix/a_file.h", new FakeSourcePath("different/path/to/a_file.h"),
            "prefix/file.h", new FakeSourcePath("file.h")),
        AppleDescriptions.convertToFlatCxxHeaders(
            Paths.get("prefix"),
            new SourcePathResolver(new BuildRuleResolver()).deprecatedPathFunction(),
            ImmutableSet.<SourcePath>of(
                new FakeSourcePath("path/to/some_file.h"),
                new FakeSourcePath("path/to/another_file.h"),
                new FakeSourcePath("different/path/to/a_file.h"),
                new FakeSourcePath("file.h"))));
  }

  @Test
  public void convertToFlatCxxHeadersWithoutPrefix() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "some_file.h", new FakeSourcePath("path/to/some_file.h"),
            "another_file.h", new FakeSourcePath("path/to/another_file.h"),
            "a_file.h", new FakeSourcePath("different/path/to/a_file.h"),
            "file.h", new FakeSourcePath("file.h")),
        AppleDescriptions.convertToFlatCxxHeaders(
            Paths.get(""),
            new SourcePathResolver(new BuildRuleResolver()).deprecatedPathFunction(),
            ImmutableSet.<SourcePath>of(
                new FakeSourcePath("path/to/some_file.h"),
                new FakeSourcePath("path/to/another_file.h"),
                new FakeSourcePath("different/path/to/a_file.h"),
                new FakeSourcePath("file.h"))));
  }

  @Test
  public void expandSdkVariableReferences() {
    Path appleSdkRoot = Paths.get("Root");
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(appleSdkRoot)
            .addToolchainPaths(appleSdkRoot.resolve("Toolchain"))
            .setPlatformPath(appleSdkRoot.resolve("Platform"))
            .setSdkPath(appleSdkRoot.resolve("SDK"))
            .build();

    Function<ImmutableList<String>, ImmutableList<String>> expandSdkVariableRefs =
        AppleDescriptions.expandSdkVariableReferencesFunction(appleSdkPaths);

    ImmutableList<String> expandedRefs = expandSdkVariableRefs.apply(
        ImmutableList.of(
            "-Ifoo/bar/baz",
            "-L$DEVELOPER_DIR/blech",
            "-I$SDKROOT/quux",
            "-F$PLATFORM_DIR/xyzzy"));

    assertEquals(
        ImmutableList.of(
            "-Ifoo/bar/baz",
            "-LRoot/blech",
            "-IRoot/SDK/quux",
            "-FRoot/Platform/xyzzy"),
        expandedRefs);
  }

}
