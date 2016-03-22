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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

public class CxxDescriptionEnhancerTest {

  @Test
  public void libraryTestIncludesPrivateHeadersOfLibraryUnderTest() throws Exception {
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");

    BuildRuleParams libParams = new FakeBuildRuleParamsBuilder(libTarget).build();
    FakeCxxLibrary libRule = new FakeCxxLibrary(
        libParams,
        pathResolver,
        BuildTargetFactory.newInstance("//:header"),
        BuildTargetFactory.newInstance("//:symlink"),
        BuildTargetFactory.newInstance("//:privateheader"),
        BuildTargetFactory.newInstance("//:privatesymlink"),
        new FakeBuildRule("//:archive", pathResolver),
        new FakeBuildRule("//:shared", pathResolver),
        Paths.get("output/path/lib.so"),
        "lib.so",
        // Ensure the test is listed as a dep of the lib.
        ImmutableSortedSet.of(testTarget)
    );

    BuildRuleParams testParams = new FakeBuildRuleParamsBuilder(testTarget)
        .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(libRule))
        .build();

    ImmutableList<CxxPreprocessorInput> combinedInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            testParams,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableMultimap.<CxxSource.Type, String>of(),
            ImmutableList.<HeaderSymlinkTree>of(),
            ImmutableSet.<FrameworkPath>of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                FluentIterable.from(testParams.getDeps())
                    .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

    Set<SourcePath> roots = new HashSet<>();
    for (CxxHeaders headers : CxxPreprocessorInput.concat(combinedInput).getIncludes()) {
      roots.add(headers.getRoot());
    }
    assertThat(
        "Test of library should include both public and private headers",
        roots,
        Matchers.<SourcePath>hasItems(
            new BuildTargetSourcePath(BuildTargetFactory.newInstance("//:symlink")),
            new BuildTargetSourcePath(BuildTargetFactory.newInstance("//:privatesymlink"))));
  }

  @Test
  public void nonTestLibraryDepDoesNotIncludePrivateHeadersOfLibrary() throws Exception {
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    BuildRuleParams libParams = new FakeBuildRuleParamsBuilder(libTarget).build();
    FakeCxxLibrary libRule = new FakeCxxLibrary(
        libParams,
        pathResolver,
        BuildTargetFactory.newInstance("//:header"),
        BuildTargetFactory.newInstance("//:symlink"),
        BuildTargetFactory.newInstance("//:privateheader"),
        BuildTargetFactory.newInstance("//:privatesymlink"),
        new FakeBuildRule("//:archive", pathResolver),
        new FakeBuildRule("//:shared", pathResolver),
        Paths.get("output/path/lib.so"),
        "lib.so",
        // This library has no tests.
        ImmutableSortedSet.<BuildTarget>of()
    );

    BuildTarget otherLibDepTarget = BuildTargetFactory.newInstance("//:other");
    BuildRuleParams otherLibDepParams = new FakeBuildRuleParamsBuilder(otherLibDepTarget)
        .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(libRule))
        .build();

    ImmutableList<CxxPreprocessorInput> otherInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            otherLibDepParams,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableMultimap.<CxxSource.Type, String>of(),
            ImmutableList.<HeaderSymlinkTree>of(),
            ImmutableSet.<FrameworkPath>of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                FluentIterable.from(otherLibDepParams.getDeps())
                    .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

    Set<SourcePath> roots = new HashSet<>();
    for (CxxHeaders headers : CxxPreprocessorInput.concat(otherInput).getIncludes()) {
      roots.add(headers.getRoot());
    }
    assertThat(
        "Non-test rule with library dep should include public and not private headers",
        roots,
        allOf(
            hasItem(new BuildTargetSourcePath(BuildTargetFactory.newInstance("//:symlink"))),
            not(hasItem(
                new BuildTargetSourcePath(BuildTargetFactory.newInstance("//:privatesymlink"))))));
  }

  @Test
  public void buildTargetsWithDifferentFlavorsProduceDifferentDefaultSonames() {
    BuildTarget target1 = BuildTargetFactory.newInstance("//:rule#one");
    BuildTarget target2 = BuildTargetFactory.newInstance("//:rule#two");
    assertNotEquals(
        CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
            target1,
            CxxPlatformUtils.DEFAULT_PLATFORM),
        CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
            target2,
            CxxPlatformUtils.DEFAULT_PLATFORM));
  }

  @Test
  public void testSonameExpansion() {
    assertThat(soname("libfoo.so", "dylib", "%s.dylib"), equalTo("libfoo.so"));
    assertThat(soname("libfoo.$(ext)", "good", "%s.bad"), equalTo("libfoo.good"));
    assertThat(soname("libfoo.$(ext 2.3)", "bad", "%s.good"), equalTo("libfoo.2.3.good"));
    assertThat(soname("libfoo.$(ext 2.3)", "bad", "good.%s"), equalTo("libfoo.good.2.3"));
    assertThat(soname("libfoo.$(ext 2.3)", "bad", "windows"), equalTo("libfoo.windows"));
  }

  /**
   * Just a helper to make this shorter to write.
   */
  private static String soname(String declared, String extension, String versionedFormat) {
    return CxxDescriptionEnhancer.getNonDefaultSharedLibrarySoname(
        declared, extension, versionedFormat);
  }
}
