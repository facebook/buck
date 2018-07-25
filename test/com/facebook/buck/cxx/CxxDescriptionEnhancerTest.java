/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxDescriptionEnhancerTest {

  @Test
  public void libraryTestIncludesPrivateHeadersOfLibraryUnderTest() {
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");

    BuildRuleParams libParams = TestBuildRuleParams.create();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeCxxLibrary libRule =
        new FakeCxxLibrary(
            libTarget,
            new FakeProjectFilesystem(),
            libParams,
            BuildTargetFactory.newInstance("//:header"),
            BuildTargetFactory.newInstance("//:symlink"),
            BuildTargetFactory.newInstance("//:privateheader"),
            BuildTargetFactory.newInstance("//:privatesymlink"),
            new FakeBuildRule("//:archive"),
            new FakeBuildRule("//:shared"),
            Paths.get("output/path/lib.so"),
            "lib.so",
            // Ensure the test is listed as a dep of the lib.
            ImmutableSortedSet.of(testTarget));

    ImmutableSet<BuildRule> deps = ImmutableSortedSet.of(libRule);

    ImmutableList<CxxPreprocessorInput> combinedInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            testTarget,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            graphBuilder,
            deps,
            ImmutableMultimap.of(),
            ImmutableList.of(),
            ImmutableSet.of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, deps),
            ImmutableList.of(),
            Optional.empty(),
            ImmutableSortedSet.of());

    Set<SourcePath> roots = new HashSet<>();
    for (CxxHeaders headers : CxxPreprocessorInput.concat(combinedInput).getIncludes()) {
      roots.add(headers.getRoot());
    }
    assertThat(
        "Test of library should include both public and private headers",
        roots,
        Matchers.hasItems(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:symlink")),
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:privatesymlink"))));
  }

  @Test
  public void libraryTestIncludesPublicHeadersOfDependenciesOfLibraryUnderTest() {
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildTarget otherlibTarget = BuildTargetFactory.newInstance("//:otherlib");
    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");

    BuildRuleParams otherlibParams = TestBuildRuleParams.create();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeCxxLibrary otherlibRule =
        new FakeCxxLibrary(
            otherlibTarget,
            new FakeProjectFilesystem(),
            otherlibParams,
            BuildTargetFactory.newInstance("//:otherheader"),
            BuildTargetFactory.newInstance("//:othersymlink"),
            BuildTargetFactory.newInstance("//:otherprivateheader"),
            BuildTargetFactory.newInstance("//:otherprivatesymlink"),
            new FakeBuildRule("//:archive"),
            new FakeBuildRule("//:shared"),
            Paths.get("output/path/lib.so"),
            "lib.so",
            // This library has no tests.
            ImmutableSortedSet.of());

    BuildRuleParams libParams =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(otherlibRule));
    FakeCxxLibrary libRule =
        new FakeCxxLibrary(
            libTarget,
            new FakeProjectFilesystem(),
            libParams,
            BuildTargetFactory.newInstance("//:header"),
            BuildTargetFactory.newInstance("//:symlink"),
            BuildTargetFactory.newInstance("//:privateheader"),
            BuildTargetFactory.newInstance("//:privatesymlink"),
            new FakeBuildRule("//:archive"),
            new FakeBuildRule("//:shared"),
            Paths.get("output/path/lib.so"),
            "lib.so",
            // Ensure the test is listed as a dep of the lib.
            ImmutableSortedSet.of(testTarget));

    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of(libRule);

    ImmutableList<CxxPreprocessorInput> combinedInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            testTarget,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            graphBuilder,
            deps,
            ImmutableMultimap.of(),
            ImmutableList.of(),
            ImmutableSet.of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, deps),
            ImmutableList.of(),
            Optional.empty(),
            ImmutableSortedSet.of());

    Set<SourcePath> roots = new HashSet<>();
    for (CxxHeaders headers : CxxPreprocessorInput.concat(combinedInput).getIncludes()) {
      roots.add(headers.getRoot());
    }
    assertThat(
        "Test of library should include public dependency headers",
        Iterables.transform(
            CxxPreprocessorInput.concat(combinedInput).getIncludes(), CxxHeaders::getRoot),
        allOf(
            hasItem(
                DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:othersymlink"))),
            not(
                hasItem(
                    DefaultBuildTargetSourcePath.of(
                        BuildTargetFactory.newInstance("//:otherprivatesymlink"))))));
  }

  @Test
  public void nonTestLibraryDepDoesNotIncludePrivateHeadersOfLibrary() {
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    BuildRuleParams libParams = TestBuildRuleParams.create();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeCxxLibrary libRule =
        new FakeCxxLibrary(
            libTarget,
            new FakeProjectFilesystem(),
            libParams,
            BuildTargetFactory.newInstance("//:header"),
            BuildTargetFactory.newInstance("//:symlink"),
            BuildTargetFactory.newInstance("//:privateheader"),
            BuildTargetFactory.newInstance("//:privatesymlink"),
            new FakeBuildRule("//:archive"),
            new FakeBuildRule("//:shared"),
            Paths.get("output/path/lib.so"),
            "lib.so",
            // This library has no tests.
            ImmutableSortedSet.of());

    BuildTarget otherLibDepTarget = BuildTargetFactory.newInstance("//:other");
    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of(libRule);

    ImmutableList<CxxPreprocessorInput> otherInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            otherLibDepTarget,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            graphBuilder,
            deps,
            ImmutableMultimap.of(),
            ImmutableList.of(),
            ImmutableSet.of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, deps),
            ImmutableList.of(),
            Optional.empty(),
            ImmutableSortedSet.of());

    Set<SourcePath> roots = new HashSet<>();
    for (CxxHeaders headers : CxxPreprocessorInput.concat(otherInput).getIncludes()) {
      roots.add(headers.getRoot());
    }
    assertThat(
        "Non-test rule with library dep should include public and not private headers",
        roots,
        allOf(
            hasItem(DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:symlink"))),
            not(
                hasItem(
                    DefaultBuildTargetSourcePath.of(
                        BuildTargetFactory.newInstance("//:privatesymlink"))))));
  }

  @Test
  public void testSonameExpansion() {
    assertThat(soname("libfoo.so", "dylib", "%s.dylib"), equalTo("libfoo.so"));
    assertThat(soname("libfoo.$(ext)", "good", "%s.bad"), equalTo("libfoo.good"));
    assertThat(soname("libfoo.$(ext 2.3)", "bad", "%s.good"), equalTo("libfoo.2.3.good"));
    assertThat(soname("libfoo.$(ext 2.3)", "bad", "good.%s"), equalTo("libfoo.good.2.3"));
    assertThat(soname("libfoo.$(ext 2.3)", "bad", "windows"), equalTo("libfoo.windows"));
  }

  /** Just a helper to make this shorter to write. */
  private static String soname(String declared, String extension, String versionedFormat) {
    return CxxDescriptionEnhancer.getNonDefaultSharedLibrarySoname(
        declared, extension, versionedFormat);
  }
}
