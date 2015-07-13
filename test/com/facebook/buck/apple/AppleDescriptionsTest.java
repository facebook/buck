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

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleDescriptionsTest {

  @Test
  public void parseAppleHeadersForUseFromOtherTargetsFromSet() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "prefix/some_file.h", new TestSourcePath("path/to/some_file.h"),
            "prefix/another_file.h", new TestSourcePath("path/to/another_file.h"),
            "prefix/a_file.h", new TestSourcePath("different/path/to/a_file.h"),
            "prefix/file.h", new TestSourcePath("file.h")),
        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction(),
            Paths.get("prefix"),
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.<SourcePath>of(
                    new TestSourcePath("path/to/some_file.h"),
                    new TestSourcePath("path/to/another_file.h"),
                    new TestSourcePath("different/path/to/a_file.h"),
                    new TestSourcePath("file.h")))));
  }

  @Test
  public void parseAppleHeadersForUseFromTheSameFromSet() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "some_file.h", new TestSourcePath("path/to/some_file.h"),
            "another_file.h", new TestSourcePath("path/to/another_file.h"),
            "a_file.h", new TestSourcePath("different/path/to/a_file.h"),
            "file.h", new TestSourcePath("file.h")),
        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction(),
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.<SourcePath>of(
                    new TestSourcePath("path/to/some_file.h"),
                    new TestSourcePath("path/to/another_file.h"),
                    new TestSourcePath("different/path/to/a_file.h"),
                    new TestSourcePath("file.h")))));
  }

  @Test
  public void parseAppleHeadersForUseFromOtherTargetsFromMap() {
    ImmutableMap<String, SourcePath> headerMap = ImmutableMap.<String, SourcePath>of(
        "virtual/path.h", new TestSourcePath("path/to/some_file.h"),
        "another/path.h", new TestSourcePath("path/to/another_file.h"),
        "another/file.h", new TestSourcePath("different/path/to/a_file.h"),
        "file.h", new TestSourcePath("file.h"));
    assertEquals(
        headerMap,
        AppleDescriptions.parseAppleHeadersForUseFromOtherTargets(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction(),
            Paths.get("prefix"),
            SourceList.ofNamedSources(headerMap)));
  }

  @Test
  public void parseAppleHeadersForUseFromTheSameTargetFromMap() {
    ImmutableMap<String, SourcePath> headerMap = ImmutableMap.<String, SourcePath>of(
        "virtual/path.h", new TestSourcePath("path/to/some_file.h"),
        "another/path.h", new TestSourcePath("path/to/another_file.h"),
        "another/file.h", new TestSourcePath("different/path/to/a_file.h"),
        "file.h", new TestSourcePath("file.h"));
    assertEquals(
        ImmutableMap.of(),
        AppleDescriptions.parseAppleHeadersForUseFromTheSameTarget(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction(),
            SourceList.ofNamedSources(headerMap)));
  }

  @Test
  public void convertToFlatCxxHeadersWithPrefix() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "prefix/some_file.h", new TestSourcePath("path/to/some_file.h"),
            "prefix/another_file.h", new TestSourcePath("path/to/another_file.h"),
            "prefix/a_file.h", new TestSourcePath("different/path/to/a_file.h"),
            "prefix/file.h", new TestSourcePath("file.h")),
        AppleDescriptions.convertToFlatCxxHeaders(
            Paths.get("prefix"),
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction(),
            ImmutableSet.<SourcePath>of(
                new TestSourcePath("path/to/some_file.h"),
                new TestSourcePath("path/to/another_file.h"),
                new TestSourcePath("different/path/to/a_file.h"),
                new TestSourcePath("file.h"))));
  }

  @Test
  public void convertToFlatCxxHeadersWithoutPrefix() {
    assertEquals(
        ImmutableMap.<String, SourcePath>of(
            "some_file.h", new TestSourcePath("path/to/some_file.h"),
            "another_file.h", new TestSourcePath("path/to/another_file.h"),
            "a_file.h", new TestSourcePath("different/path/to/a_file.h"),
            "file.h", new TestSourcePath("file.h")),
        AppleDescriptions.convertToFlatCxxHeaders(
            Paths.get(""),
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction(),
            ImmutableSet.<SourcePath>of(
                new TestSourcePath("path/to/some_file.h"),
                new TestSourcePath("path/to/another_file.h"),
                new TestSourcePath("different/path/to/a_file.h"),
                new TestSourcePath("file.h"))));
  }

  @Test
  public void frameworksToLinkerFlagsTransformer() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    Function<
        ImmutableSortedSet<FrameworkPath>,
        ImmutableList<String>> frameworksToLinkerFlagsTransformer =
        AppleDescriptions.frameworksToLinkerFlagsFunction(resolver);

    ImmutableList<String> linkerFlags = frameworksToLinkerFlagsTransformer.apply(
        ImmutableSortedSet.of(
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SDKROOT,
                    Paths.get("usr/lib/libz.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(projectFilesystem, Paths.get("Vendor/Foo/libFoo.a"))),
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.DEVELOPER_DIR,
                    Paths.get("Library/Frameworks/XCTest.framework"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(projectFilesystem, Paths.get("Vendor/Bar/Bar.framework")))));

    assertEquals(
        ImmutableList.of(
            "-lz",
            "-framework", "XCTest",
            "-framework", "Bar",
            "-lFoo"),
        linkerFlags);
  }

  @Test
  public void frameworksToSearchPathsTransformer() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    Path appleSdkRoot = Paths.get("Root");
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(appleSdkRoot)
            .addToolchainPaths(appleSdkRoot.resolve("Toolchain"))
            .setPlatformPath(appleSdkRoot.resolve("Platform"))
            .setSdkPath(appleSdkRoot.resolve("SDK"))
            .build();

    Function<
        ImmutableSortedSet<FrameworkPath>,
        ImmutableList<Path>> frameworksToSearchPathsTransformer =
        AppleDescriptions.frameworksToSearchPathsFunction(resolver, appleSdkPaths);

    ImmutableList<Path> searchPaths = frameworksToSearchPathsTransformer.apply(
        ImmutableSortedSet.of(
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SDKROOT,
                    Paths.get("usr/lib/libz.dylib"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(projectFilesystem, Paths.get("Vendor/Foo/libFoo.a"))),
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.DEVELOPER_DIR,
                    Paths.get("Library/Frameworks/XCTest.framework"))),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(projectFilesystem, Paths.get("Vendor/Bar/Bar.framework")))));

    assertEquals(
        ImmutableList.of(
            Paths.get("Root/SDK/usr/lib"),
            Paths.get("Root/Library/Frameworks"),
            Paths.get("Vendor/Bar"),
            Paths.get("Vendor/Foo")),
        searchPaths);
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
