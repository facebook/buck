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

package com.facebook.buck.swift;

import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.FakeAppleRuleDescriptions;
import com.facebook.buck.cxx.FakeCxxLibrary;
import com.facebook.buck.cxx.HeaderSymlinkTreeWithHeaderMap;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class SwiftLibraryIntegrationTest {
  @Rule
  public final TemporaryPaths tmpDir = new TemporaryPaths();

  @Test
  public void headersOfDependentTargetsAreIncluded() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // The output path used by the buildable for the link tree.
    BuildTarget symlinkTarget = BuildTargetFactory.newInstance("//:symlink");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());
    Path symlinkTreeRoot = projectFilesystem.resolve(
        BuildTargets.getGenPath(projectFilesystem, symlinkTarget, "%s/symlink-tree-root"));

    // Setup the map representing the link tree.
    ImmutableMap<Path, SourcePath> links = ImmutableMap.of();

    BuildRule symlinkTreeBuildRule = new HeaderSymlinkTreeWithHeaderMap(
        new FakeBuildRuleParamsBuilder(symlinkTarget).build(),
        pathResolver,
        symlinkTreeRoot,
        links);
    resolver.addToIndex(symlinkTreeBuildRule);

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildRuleParams libParams = new FakeBuildRuleParamsBuilder(libTarget).build();
    FakeCxxLibrary depRule = new FakeCxxLibrary(
        libParams,
        pathResolver,
        BuildTargetFactory.newInstance("//:header"),
        symlinkTarget,
        BuildTargetFactory.newInstance("//:privateheader"),
        BuildTargetFactory.newInstance("//:privatesymlink"),
        new FakeBuildRule("//:archive", pathResolver),
        new FakeBuildRule("//:shared", pathResolver),
        Paths.get("output/path/lib.so"),
        "lib.so",
        ImmutableSortedSet.of()
    );

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-x86_64")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeclaredDeps(ImmutableSortedSet.of(depRule))
        .build();

    SwiftLibraryDescription.Arg args =
        FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createUnpopulatedConstructorArg();
    args.moduleName = Optional.empty();
    args.srcs = ImmutableSortedSet.of();
    args.compilerFlags = ImmutableList.of();
    args.frameworks = ImmutableSortedSet.of();
    args.libraries = ImmutableSortedSet.of();
    args.enableObjcInterop = Optional.empty();
    args.supportedPlatformsRegex = Optional.empty();
    args.headersSearchPath = ImmutableMap.of();

    SwiftCompile buildRule = (SwiftCompile) FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION
        .createBuildRule(TargetGraph.EMPTY, params, resolver, args);

    ImmutableList<String> swiftIncludeArgs = buildRule.getSwiftIncludeArgs();

    assertThat(swiftIncludeArgs.size(), Matchers.equalTo(1));
    assertThat(swiftIncludeArgs.get(0), Matchers.startsWith("-I"));
    assertThat(swiftIncludeArgs.get(0), Matchers.endsWith("symlink.hmap"));
  }

}
