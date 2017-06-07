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
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.FakeCxxLibrary;
import com.facebook.buck.cxx.HeaderSymlinkTreeWithHeaderMap;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeTargetNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftLibraryIntegrationTest {
  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private BuildRuleResolver resolver;
  private SourcePathResolver pathResolver;
  private SourcePathRuleFinder ruleFinder;

  @Before
  public void setUp() {
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
  }

  @Test
  public void headersOfDependentTargetsAreIncluded() throws Exception {
    // The output path used by the buildable for the link tree.
    BuildTarget symlinkTarget = BuildTargetFactory.newInstance("//:symlink");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());
    Path symlinkTreeRoot =
        BuildTargets.getGenPath(projectFilesystem, symlinkTarget, "%s/symlink-tree-root");

    // Setup the map representing the link tree.
    ImmutableMap<Path, SourcePath> links = ImmutableMap.of();

    HeaderSymlinkTreeWithHeaderMap symlinkTreeBuildRule =
        HeaderSymlinkTreeWithHeaderMap.create(
            symlinkTarget, projectFilesystem, symlinkTreeRoot, links, ruleFinder);
    resolver.addToIndex(symlinkTreeBuildRule);

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildRuleParams libParams = new FakeBuildRuleParamsBuilder(libTarget).build();
    FakeCxxLibrary depRule =
        new FakeCxxLibrary(
            libParams,
            BuildTargetFactory.newInstance("//:header"),
            symlinkTarget,
            BuildTargetFactory.newInstance("//:privateheader"),
            BuildTargetFactory.newInstance("//:privatesymlink"),
            new FakeBuildRule("//:archive", pathResolver),
            new FakeBuildRule("//:shared", pathResolver),
            Paths.get("output/path/lib.so"),
            "lib.so",
            ImmutableSortedSet.of());

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-x86_64");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(buildTarget)
            .setDeclaredDeps(ImmutableSortedSet.of(depRule))
            .build();

    SwiftLibraryDescriptionArg args = createDummySwiftArg();

    SwiftCompile buildRule =
        (SwiftCompile)
            FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createBuildRule(
                TargetGraph.EMPTY,
                params,
                resolver,
                TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
                args);

    ImmutableList<String> swiftIncludeArgs = buildRule.getSwiftIncludeArgs(pathResolver);

    assertThat(swiftIncludeArgs.size(), Matchers.equalTo(1));
    assertThat(swiftIncludeArgs.get(0), Matchers.startsWith("-I"));
    assertThat(swiftIncludeArgs.get(0), Matchers.endsWith("symlink.hmap"));
  }

  @Test
  public void testSwiftCompileAndLinkArgs() throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-x86_64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(swiftCompileTarget).build();

    SwiftLibraryDescriptionArg args = createDummySwiftArg();
    SwiftCompile buildRule =
        (SwiftCompile)
            FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createBuildRule(
                TargetGraph.EMPTY,
                params,
                resolver,
                TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
                args);
    resolver.addToIndex(buildRule);

    ImmutableList<Arg> astArgs = buildRule.getAstLinkArgs();
    assertThat(astArgs, Matchers.hasSize(3));
    assertThat(astArgs.get(0), Matchers.equalTo(StringArg.of("-Xlinker")));
    assertThat(astArgs.get(1), Matchers.equalTo(StringArg.of("-add_ast_path")));

    assertThat(astArgs.get(2), Matchers.instanceOf(SourcePathArg.class));
    SourcePathArg sourcePathArg = (SourcePathArg) astArgs.get(2);
    assertThat(
        sourcePathArg.getPath(),
        Matchers.equalTo(
            new ExplicitBuildTargetSourcePath(
                swiftCompileTarget,
                pathResolver
                    .getRelativePath(buildRule.getSourcePathToOutput())
                    .resolve("bar.swiftmodule"))));

    Arg objArg = buildRule.getFileListLinkArg();
    assertThat(objArg, Matchers.instanceOf(FileListableLinkerInputArg.class));
    FileListableLinkerInputArg fileListArg = (FileListableLinkerInputArg) objArg;
    ExplicitBuildTargetSourcePath fileListSourcePath =
        new ExplicitBuildTargetSourcePath(
            swiftCompileTarget,
            pathResolver.getRelativePath(buildRule.getSourcePathToOutput()).resolve("bar.o"));
    assertThat(fileListArg.getPath(), Matchers.equalTo(fileListSourcePath));

    BuildTarget linkTarget = buildTarget.withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    CxxLink linkRule =
        (CxxLink)
            FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createBuildRule(
                TargetGraphFactory.newInstance(FakeTargetNodeBuilder.build(buildRule)),
                params.withBuildTarget(linkTarget),
                resolver,
                TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
                args);

    assertThat(linkRule.getArgs(), Matchers.hasItem(objArg));
    assertThat(
        linkRule.getArgs(), Matchers.not(Matchers.hasItem(SourcePathArg.of(fileListSourcePath))));
  }

  private SwiftLibraryDescriptionArg createDummySwiftArg() {
    return SwiftLibraryDescriptionArg.builder().setName("dummy").build();
  }
}
