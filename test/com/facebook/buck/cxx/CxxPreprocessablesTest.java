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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

public class CxxPreprocessablesTest {

  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(new FakeBuckConfig());

  private static <T> void assertContains(ImmutableList<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  private static class FakeCxxPreprocessorDep extends FakeBuildRule
      implements CxxPreprocessorDep {

    private final CxxPreprocessorInput input;

    public FakeCxxPreprocessorDep(
        BuildRuleParams params,
        SourcePathResolver resolver,
        CxxPreprocessorInput input) {
      super(params, resolver);
      this.input = Preconditions.checkNotNull(input);
    }

    @Override
    public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform) {
      return input;
    }

  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      BuildTarget target,
      SourcePathResolver resolver,
      CxxPreprocessorInput input,
      BuildRule... deps) {
    return new FakeCxxPreprocessorDep(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver, input);
  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      String target,
      SourcePathResolver resolver,
      CxxPreprocessorInput input,
      BuildRule... deps) {
    return createFakeCxxPreprocessorDep(
        BuildTargetFactory.newInstance(target),
        resolver,
        input,
        deps);
  }

  private static FakeBuildRule createFakeBuildRule(
      BuildTarget target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver);
  }

  @Test
  public void resolveHeaderMap() {
    BuildTarget target = BuildTargetFactory.newInstance("//hello/world:test");
    ImmutableMap<String, SourcePath> headerMap = ImmutableMap.<String, SourcePath>of(
        "foo/bar.h", new TestSourcePath("header1.h"),
        "foo/hello.h", new TestSourcePath("header2.h"));

    // Verify that the resolveHeaderMap returns sane results.
    ImmutableMap<Path, SourcePath> expected = ImmutableMap.<Path, SourcePath>of(
        target.getBasePath().resolve("foo/bar.h"), new TestSourcePath("header1.h"),
        target.getBasePath().resolve("foo/hello.h"), new TestSourcePath("header2.h"));
    ImmutableMap<Path, SourcePath> actual = CxxPreprocessables.resolveHeaderMap(
        target.getBasePath(), headerMap);
    assertEquals(expected, actual);
  }

  @Test
  public void getTransitiveCxxPreprocessorInput() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());

    // Setup a simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget1 = BuildTargetFactory.newInstance("//:cpp1");
    CxxPreprocessorInput input1 = CxxPreprocessorInput.builder()
        .addRules(cppDepTarget1)
        .putPreprocessorFlags(CxxSource.Type.C, "-Dtest=yes")
        .putPreprocessorFlags(CxxSource.Type.CXX, "-Dtest=yes")
        .addIncludeRoots(Paths.get("foo/bar"), Paths.get("hello"))
        .addSystemIncludeRoots(Paths.get("/usr/include"))
        .build();
    BuildTarget depTarget1 = BuildTargetFactory.newInstance("//:dep1");
    FakeCxxPreprocessorDep dep1 = createFakeCxxPreprocessorDep(depTarget1, pathResolver, input1);

    // Setup another simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget2 = BuildTargetFactory.newInstance("//:cpp2");
    CxxPreprocessorInput input2 = CxxPreprocessorInput.builder()
        .addRules(cppDepTarget2)
        .putPreprocessorFlags(CxxSource.Type.C, "-DBLAH")
        .putPreprocessorFlags(CxxSource.Type.CXX, "-DBLAH")
        .addIncludeRoots(Paths.get("goodbye"))
        .addSystemIncludeRoots(Paths.get("test"))
        .build();
    BuildTarget depTarget2 = BuildTargetFactory.newInstance("//:dep2");
    FakeCxxPreprocessorDep dep2 = createFakeCxxPreprocessorDep(depTarget2, pathResolver, input2);

    // Create a normal dep which depends on the two CxxPreprocessorDep rules above.
    BuildTarget depTarget3 = BuildTargetFactory.newInstance("//:dep3");
    CxxPreprocessorInput nothing = CxxPreprocessorInput.EMPTY;
    FakeCxxPreprocessorDep dep3 = createFakeCxxPreprocessorDep(depTarget3,
        pathResolver,
        nothing, dep1, dep2);

    // Verify that getTransitiveCxxPreprocessorInput gets all CxxPreprocessorInput objects
    // from the relevant rules above.
    CxxPreprocessorInput expected = CxxPreprocessorInput.concat(
        ImmutableList.of(input1, input2));
    CxxPreprocessorInput actual = CxxPreprocessables.getTransitiveCxxPreprocessorInput(
        cxxPlatform,
        ImmutableList.<BuildRule>of(dep3));
    assertEquals(expected, actual);
  }

  @Test
  public void createHeaderSymlinkTreeBuildRuleHasNoDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    // Setup up the main build target and build params, which some random dep.  We'll make
    // sure the dep doesn't get propagated to the symlink rule below.
    FakeBuildRule dep = createFakeBuildRule(
        BuildTargetFactory.newInstance("//random:dep"),
        pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    Path root = Paths.get("root");

    // Setup a simple genrule we can wrap in a BuildTargetSourcePath to model a input source
    // that is built by another rule.
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
        .setOut("foo/bar.o")
        .build(resolver);

    // Setup the link map with both a regular path-based source path and one provided by
    // another build rule.
    ImmutableMap<Path, SourcePath> links = ImmutableMap.<Path, SourcePath>of(
        Paths.get("link1"), new TestSourcePath("hello"),
        Paths.get("link2"), new BuildTargetSourcePath(genrule.getBuildTarget()));

    // Build our symlink tree rule using the helper method.
    SymlinkTree symlinkTree = CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        pathResolver,
        target,
        params,
        root,
        links);

    // Verify that the symlink tree has no deps.  This is by design, since setting symlinks can
    // be done completely independently from building the source that the links point to and
    // independently from the original deps attached to the input build rule params.
    assertTrue(symlinkTree.getDeps().isEmpty());
  }

  @Test
  public void getTransitiveNativeLinkableInputDoesNotTraversePastNonNativeLinkables() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());

    // Create a native linkable that sits at the bottom of the dep chain.
    String sentinal = "bottom";
    CxxPreprocessorInput bottomInput = CxxPreprocessorInput.builder()
        .putPreprocessorFlags(CxxSource.Type.C, sentinal)
        .build();
    BuildRule bottom = createFakeCxxPreprocessorDep("//:bottom", pathResolver, bottomInput);

    // Create a non-native linkable that sits in the middle of the dep chain, preventing
    // traversals to the bottom native linkable.
    BuildRule middle = new FakeBuildRule("//:middle", pathResolver, bottom);

    // Create a native linkable that sits at the top of the dep chain.
    CxxPreprocessorInput topInput = CxxPreprocessorInput.EMPTY;
    BuildRule top = createFakeCxxPreprocessorDep("//:top", pathResolver, topInput, middle);

    // Now grab all input via traversing deps and verify that the middle rule prevents pulling
    // in the bottom input.
    CxxPreprocessorInput totalInput =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            ImmutableList.of(top));
    assertTrue(bottomInput.getPreprocessorFlags().get(CxxSource.Type.C).contains(sentinal));
    assertFalse(totalInput.getPreprocessorFlags().get(CxxSource.Type.C).contains(sentinal));
  }

  @Test
  public void createPreprocessBuildRulePropagatesCxxPreprocessorDeps() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule dep = resolver.addToIndex(
        new FakeBuildRule(
            "//:dep1",
            new SourcePathResolver(new BuildRuleResolver())));

    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addRules(dep.getBuildTarget())
            .build();

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = ImmutableCxxSource.of(CxxSource.Type.CXX, input);

    Map.Entry<String, CxxSource> entry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            resolver,
            CXX_PLATFORM,
            cxxPreprocessorInput,
            /* pic */ false,
            name,
            cxxSource);
    BuildRule cxxPreprocess = pathResolver.getRule(entry.getValue().getPath()).get();
    assertEquals(ImmutableSortedSet.<BuildRule>of(dep), cxxPreprocess.getDeps());
  }

  @Test
  public void preprocessFlagsFromPlatformArePropagated() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    CxxPreprocessorInput cxxPreprocessorInput = CxxPreprocessorInput.EMPTY;

    String name = "source.cpp";
    CxxSource cxxSource = ImmutableCxxSource.of(CxxSource.Type.CXX, new TestSourcePath(name));

    ImmutableList<String> platformFlags = ImmutableList.of("-some", "-flags");
    CxxPlatform platform = DefaultCxxPlatforms.build(
        new FakeBuckConfig(
            ImmutableMap.<String, Map<String, String>>of(
                "cxx", ImmutableMap.of("cxxppflags", Joiner.on(" ").join(platformFlags)))));

    // Verify that platform flags make it to the compile rule.
    Map.Entry<String, CxxSource> output =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            resolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            name,
            cxxSource);
    CxxPreprocess cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(output.getValue().getPath()).get();
    assertNotEquals(
        -1,
        Collections.indexOfSubList(cxxPreprocess.getFlags(), platformFlags));
  }

  @Test
  public void checkCorrectFlagsAreUsed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    Joiner space = Joiner.on(" ");

    ImmutableList<String> explicitCppflags = ImmutableList.of("-explicit-cppflag");
    ImmutableList<String> explicitCxxppflags = ImmutableList.of("-explicit-cxxppflag");
    CxxPreprocessorInput cxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .putAllPreprocessorFlags(CxxSource.Type.C, explicitCppflags)
            .putAllPreprocessorFlags(CxxSource.Type.CXX, explicitCxxppflags)
            .build();

    ImmutableList<String> asppflags = ImmutableList.of("-asppflag", "-asppflag");

    SourcePath cpp = new TestSourcePath("cpp");
    ImmutableList<String> cppflags = ImmutableList.of("-cppflag", "-cppflag");

    SourcePath cxxpp = new TestSourcePath("cxxpp");
    ImmutableList<String> cxxppflags = ImmutableList.of("-cxxppflag", "-cxxppflag");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.<String, String>builder()
                .put("asppflags", space.join(asppflags))
                .put("cpp", sourcePathResolver.getPath(cpp).toString())
                .put("cppflags", space.join(cppflags))
                .put("cxxpp", sourcePathResolver.getPath(cxxpp).toString())
                .put("cxxppflags", space.join(cxxppflags))
                .build()),
        filesystem);
    CxxPlatform platform = DefaultCxxPlatforms.build(buckConfig);

    String cSourceName = "test.c";
    CxxSource cSource = ImmutableCxxSource.of(CxxSource.Type.C, new TestSourcePath(cSourceName));
    Map.Entry<String, CxxSource> cPreprocessEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            cSourceName,
            cSource);
    CxxPreprocess cPreprocess =
        (CxxPreprocess) sourcePathResolver.getRule(cPreprocessEntry.getValue().getPath()).get();
    assertContains(cPreprocess.getFlags(), explicitCppflags);
    assertContains(cPreprocess.getFlags(), cppflags);

    String cxxSourceName = "test.cpp";
    CxxSource cxxSource = ImmutableCxxSource.of(
        CxxSource.Type.CXX,
        new TestSourcePath(cxxSourceName));
    Map.Entry<String, CxxSource> cxxPreprocessEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            cxxSourceName,
            cxxSource);
    CxxPreprocess cxxPreprocess =
        (CxxPreprocess) sourcePathResolver.getRule(cxxPreprocessEntry.getValue().getPath()).get();
    assertContains(cxxPreprocess.getFlags(), explicitCxxppflags);
    assertContains(cxxPreprocess.getFlags(), cxxppflags);

    String assemblerWithCppSourceName = "test.S";
    CxxSource assemblerWithCppSource = ImmutableCxxSource.of(
        CxxSource.Type.ASSEMBLER_WITH_CPP,
        new TestSourcePath(assemblerWithCppSourceName));
    Map.Entry<String, CxxSource> assemblerWithCppCompileEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            platform,
            cxxPreprocessorInput,
            /* pic */ false,
            assemblerWithCppSourceName,
            assemblerWithCppSource);
    CxxPreprocess assemblerWithCppPreprocess =
        (CxxPreprocess) sourcePathResolver.getRule(
            assemblerWithCppCompileEntry.getValue().getPath()).get();
    assertContains(assemblerWithCppPreprocess.getFlags(), asppflags);
  }

  @Test
  public void languageFlagsArePassed() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String name = "foo/bar.cpp";
    SourcePath input = new PathSourcePath(target.getBasePath().resolve(name));
    CxxSource cxxSource = ImmutableCxxSource.of(CxxSource.Type.CXX, input);

    Map.Entry<String, CxxSource> cxxPreprocessEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    CxxPreprocess cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "c++"));

    name = "foo/bar.m";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = ImmutableCxxSource.of(CxxSource.Type.OBJC, input);

    cxxPreprocessEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "objective-c"));

    name = "foo/bar.mm";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = ImmutableCxxSource.of(CxxSource.Type.OBJCXX, input);

    cxxPreprocessEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "objective-c++"));

    name = "foo/bar.c";
    input = new PathSourcePath(target.getBasePath().resolve(name));
    cxxSource = ImmutableCxxSource.of(CxxSource.Type.C, input);

    cxxPreprocessEntry =
        CxxPreprocessables.createPreprocessBuildRule(
            params,
            buildRuleResolver,
            CXX_PLATFORM,
            CxxPreprocessorInput.EMPTY,
            /* pic */ false,
            name,
            cxxSource);
    cxxPreprocess =
        (CxxPreprocess) pathResolver.getRule(
            cxxPreprocessEntry.getValue().getPath()).get();
    assertThat(cxxPreprocess.getFlags(), Matchers.contains("-x", "c"));
  }

}
