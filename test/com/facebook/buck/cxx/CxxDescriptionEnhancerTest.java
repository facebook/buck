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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxDescriptionEnhancerTest {

  @Test
  public void createLexYaccBuildRules() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup our C++ buck config with the paths to the lex/yacc binaries.
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path lexPath = Paths.get("lex");
    filesystem.touch(lexPath);
    Path yaccPath = Paths.get("yacc");
    filesystem.touch(yaccPath);
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            "cxx", ImmutableMap.of(
                "lex", lexPath.toString(),
                "yacc", yaccPath.toString())),
        filesystem);
    CxxPlatform cxxBuckConfig = DefaultCxxPlatforms.build(new CxxBuckConfig(buckConfig));

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Setup a genrule that generates our lex source.
    String lexSourceName = "test.ll";
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule_lex");
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut(lexSourceName)
        .build(resolver);
    SourcePath lexSource = new BuildTargetSourcePath(genrule.getBuildTarget());

    // Use a regular path for our yacc source.
    String yaccSourceName = "test.yy";
    SourcePath yaccSource = new TestSourcePath(yaccSourceName);

    // Build the rules.
    CxxHeaderSourceSpec actual = CxxDescriptionEnhancer.createLexYaccBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        ImmutableList.<String>of(),
        ImmutableMap.of(lexSourceName, lexSource),
        ImmutableList.<String>of(),
        ImmutableMap.of(yaccSourceName, yaccSource));

    // Grab the generated lex rule and verify it has the genrule as a dep.
    Lex lex = (Lex) resolver.getRule(
        CxxDescriptionEnhancer.createLexBuildTarget(target, lexSourceName));
    assertNotNull(lex);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        lex.getDeps());

    // Grab the generated yacc rule and verify it has no deps.
    Yacc yacc = (Yacc) resolver.getRule(
        CxxDescriptionEnhancer.createYaccBuildTarget(target, yaccSourceName));
    assertNotNull(yacc);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(),
        yacc.getDeps());

    // Check the header/source spec is correct.
    Path lexOutputSource = CxxDescriptionEnhancer.getLexSourceOutputPath(target, lexSourceName);
    Path lexOutputHeader = CxxDescriptionEnhancer.getLexHeaderOutputPath(target, lexSourceName);
    Path yaccOutputPrefix =
        CxxDescriptionEnhancer.getYaccOutputPrefix(
            target,
            Files.getNameWithoutExtension(yaccSourceName));
    Path yaccOutputSource = Yacc.getSourceOutputPath(yaccOutputPrefix);
    Path yaccOutputHeader = Yacc.getHeaderOutputPath(yaccOutputPrefix);
    CxxHeaderSourceSpec expected =
        CxxHeaderSourceSpec.of(
            ImmutableMap.<Path, SourcePath>of(
                target.getBasePath().resolve(lexSourceName + ".h"),
                new BuildTargetSourcePath(lex.getBuildTarget(), lexOutputHeader),
                target.getBasePath().resolve(yaccSourceName + ".h"),
                new BuildTargetSourcePath(yacc.getBuildTarget(), yaccOutputHeader)),
            ImmutableMap.of(
                lexSourceName + ".cc",
                CxxSource.of(
                    CxxSource.Type.CXX,
                    new BuildTargetSourcePath(lex.getBuildTarget(), lexOutputSource),
                    ImmutableList.<String>of()),
                yaccSourceName + ".cc",
                CxxSource.of(
                    CxxSource.Type.CXX,
                    new BuildTargetSourcePath(yacc.getBuildTarget(), yaccOutputSource),
                    ImmutableList.<String>of())));
    assertEquals(expected, actual);
  }

  @Test
  public void libraryTestIncludesPrivateHeadersOfLibraryUnderTest() throws Exception {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");

    BuildRuleParams libParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(libTarget);
    FakeCxxLibrary libRule = new FakeCxxLibrary(
        libParams,
        pathResolver,
        BuildTargetFactory.newInstance("//:header"),
        BuildTargetFactory.newInstance("//:symlink"),
        Paths.get("symlink/tree/lib"),
        BuildTargetFactory.newInstance("//:privateheader"),
        BuildTargetFactory.newInstance("//:privatesymlink"),
        Paths.get("private/symlink/tree/lib"),
        new FakeBuildRule("//:archive", pathResolver),
        Paths.get("output/path/lib.a"),
        new FakeBuildRule("//:shared", pathResolver),
        Paths.get("output/path/lib.so"),
        "lib.so",
        // Ensure the test is listed as a dep of the lib.
        ImmutableSortedSet.of(testTarget)
    );

    BuildRuleParams testParams = new FakeBuildRuleParamsBuilder(testTarget)
        .setDeps(ImmutableSortedSet.<BuildRule>of(libRule))
        .build();

    ImmutableList<CxxPreprocessorInput> combinedInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            testParams,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableMultimap.<CxxSource.Type, String>of(),
            ImmutableList.<SourcePath>of(),
            ImmutableList.<SymlinkTree>of(),
            ImmutableSet.<Path>of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                FluentIterable.from(testParams.getDeps())
                    .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

    assertThat(
        "Test of library should include both public and private headers",
        CxxPreprocessorInput.concat(combinedInput).getIncludeRoots(),
        hasItems(
            Paths.get("symlink/tree/lib"),
            Paths.get("private/symlink/tree/lib")));
  }

  @Test
  public void nonTestLibraryDepDoesNotIncludePrivateHeadersOfLibrary() throws Exception {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");

    BuildRuleParams libParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(libTarget);
    FakeCxxLibrary libRule = new FakeCxxLibrary(
        libParams,
        pathResolver,
        BuildTargetFactory.newInstance("//:header"),
        BuildTargetFactory.newInstance("//:symlink"),
        Paths.get("symlink/tree/lib"),
        BuildTargetFactory.newInstance("//:privateheader"),
        BuildTargetFactory.newInstance("//:privatesymlink"),
        Paths.get("private/symlink/tree/lib"),
        new FakeBuildRule("//:archive", pathResolver),
        Paths.get("output/path/lib.a"),
        new FakeBuildRule("//:shared", pathResolver),
        Paths.get("output/path/lib.so"),
        "lib.so",
        // This library has no tests.
        ImmutableSortedSet.<BuildTarget>of()
    );

    BuildTarget otherLibDepTarget = BuildTargetFactory.newInstance("//:other");
    BuildRuleParams otherLibDepParams = new FakeBuildRuleParamsBuilder(otherLibDepTarget)
        .setDeps(ImmutableSortedSet.<BuildRule>of(libRule))
        .build();

    ImmutableList<CxxPreprocessorInput> otherInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            otherLibDepParams,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableMultimap.<CxxSource.Type, String>of(),
            ImmutableList.<SourcePath>of(),
            ImmutableList.<SymlinkTree>of(),
            ImmutableSet.<Path>of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                FluentIterable.from(otherLibDepParams.getDeps())
                    .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

    assertThat(
        "Non-test rule with library dep should include public and not private headers",
        CxxPreprocessorInput.concat(otherInput).getIncludeRoots(),
        allOf(
            hasItem(Paths.get("symlink/tree/lib")),
            not(hasItem(Paths.get("private/symlink/tree/lib")))));
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

}
