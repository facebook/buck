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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CxxPreprocessablesTest {

  private static class FakeCxxPreprocessorDep extends FakeBuildRule implements CxxPreprocessorDep {

    private final CxxPreprocessorInput input;

    public FakeCxxPreprocessorDep(
        BuildRuleParams params, SourcePathResolver resolver, CxxPreprocessorInput input) {
      super(params, resolver);
      this.input = Preconditions.checkNotNull(input);
    }

    @Override
    public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
      return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
    }

    @Override
    public CxxPreprocessorInput getCxxPreprocessorInput(
        CxxPlatform cxxPlatform, HeaderVisibility headerVisibility) {
      return input;
    }

    @Override
    public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
        CxxPlatform cxxPlatform, HeaderVisibility headerVisibility)
        throws NoSuchBuildTargetException {
      ImmutableMap.Builder<BuildTarget, CxxPreprocessorInput> builder = ImmutableMap.builder();
      builder.put(getBuildTarget(), getCxxPreprocessorInput(cxxPlatform, headerVisibility));
      for (BuildRule dep : getBuildDeps()) {
        if (dep instanceof CxxPreprocessorDep) {
          builder.putAll(
              ((CxxPreprocessorDep) dep)
                  .getTransitiveCxxPreprocessorInput(cxxPlatform, headerVisibility));
        }
      }
      return builder.build();
    }
  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      BuildTarget target,
      SourcePathResolver resolver,
      CxxPreprocessorInput input,
      BuildRule... deps) {
    return new FakeCxxPreprocessorDep(
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver,
        input);
  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      String target, SourcePathResolver resolver, CxxPreprocessorInput input, BuildRule... deps) {
    return createFakeCxxPreprocessorDep(
        BuildTargetFactory.newInstance(target), resolver, input, deps);
  }

  private static FakeBuildRule createFakeBuildRule(
      BuildTarget target, SourcePathResolver resolver, BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver);
  }

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void resolveHeaderMap() {
    BuildTarget target = BuildTargetFactory.newInstance("//hello/world:test");
    ImmutableMap<String, SourcePath> headerMap =
        ImmutableMap.of(
            "foo/bar.h", new FakeSourcePath("header1.h"),
            "foo/hello.h", new FakeSourcePath("header2.h"));

    // Verify that the resolveHeaderMap returns sane results.
    ImmutableMap<Path, SourcePath> expected =
        ImmutableMap.of(
            target.getBasePath().resolve("foo/bar.h"), new FakeSourcePath("header1.h"),
            target.getBasePath().resolve("foo/hello.h"), new FakeSourcePath("header2.h"));
    ImmutableMap<Path, SourcePath> actual =
        CxxPreprocessables.resolveHeaderMap(target.getBasePath(), headerMap);
    assertEquals(expected, actual);
  }

  @Test
  public void getTransitiveCxxPreprocessorInput() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(
            new CxxBuckConfig(FakeBuckConfig.builder().setFilesystem(filesystem).build()));

    // Setup a simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget1 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:cpp1");
    CxxPreprocessorInput input1 =
        CxxPreprocessorInput.builder()
            .addRules(cppDepTarget1)
            .putPreprocessorFlags(CxxSource.Type.C, "-Dtest=yes")
            .putPreprocessorFlags(CxxSource.Type.CXX, "-Dtest=yes")
            .build();
    BuildTarget depTarget1 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:dep1");
    FakeCxxPreprocessorDep dep1 = createFakeCxxPreprocessorDep(depTarget1, pathResolver, input1);

    // Setup another simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget2 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:cpp2");
    CxxPreprocessorInput input2 =
        CxxPreprocessorInput.builder()
            .addRules(cppDepTarget2)
            .putPreprocessorFlags(CxxSource.Type.C, "-DBLAH")
            .putPreprocessorFlags(CxxSource.Type.CXX, "-DBLAH")
            .build();
    BuildTarget depTarget2 = BuildTargetFactory.newInstance("//:dep2");
    FakeCxxPreprocessorDep dep2 = createFakeCxxPreprocessorDep(depTarget2, pathResolver, input2);

    // Create a normal dep which depends on the two CxxPreprocessorDep rules above.
    BuildTarget depTarget3 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:dep3");
    CxxPreprocessorInput nothing = CxxPreprocessorInput.EMPTY;
    FakeCxxPreprocessorDep dep3 =
        createFakeCxxPreprocessorDep(depTarget3, pathResolver, nothing, dep1, dep2);

    // Verify that getTransitiveCxxPreprocessorInput gets all CxxPreprocessorInput objects
    // from the relevant rules above.
    ImmutableList<CxxPreprocessorInput> expected = ImmutableList.of(nothing, input1, input2);
    ImmutableList<CxxPreprocessorInput> actual =
        ImmutableList.copyOf(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform, ImmutableList.<BuildRule>of(dep3)));
    assertEquals(expected, actual);
  }

  @Test
  public void createHeaderSymlinkTreeBuildRuleHasNoDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup up the main build target and build params, which some random dep.  We'll make
    // sure the dep doesn't get propagated to the symlink rule below.
    FakeBuildRule dep =
        createFakeBuildRule(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//random:dep"), pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.of(dep))
            .setProjectFilesystem(filesystem)
            .build();
    Path root = Paths.get("root");

    // Setup a simple genrule we can wrap in a ExplicitBuildTargetSourcePath to model a input source
    // that is built by another rule.
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:genrule"))
            .setOut("foo/bar.o")
            .build(resolver);

    // Setup the link map with both a regular path-based source path and one provided by
    // another build rule.
    ImmutableMap<Path, SourcePath> links =
        ImmutableMap.of(
            Paths.get("link1"),
            new FakeSourcePath("hello"),
            Paths.get("link2"),
            genrule.getSourcePathToOutput());

    // Build our symlink tree rule using the helper method.
    HeaderSymlinkTree symlinkTree =
        CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
            target,
            params.getProjectFilesystem(),
            root,
            links,
            CxxPreprocessables.HeaderMode.SYMLINK_TREE_ONLY,
            ruleFinder);

    // Verify that the symlink tree has no deps.  This is by design, since setting symlinks can
    // be done completely independently from building the source that the links point to and
    // independently from the original deps attached to the input build rule params.
    assertTrue(symlinkTree.getBuildDeps().isEmpty());
  }

  @Test
  public void getTransitiveNativeLinkableInputDoesNotTraversePastNonNativeLinkables()
      throws Exception {
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Create a native linkable that sits at the bottom of the dep chain.
    String sentinal = "bottom";
    CxxPreprocessorInput bottomInput =
        CxxPreprocessorInput.builder().putPreprocessorFlags(CxxSource.Type.C, sentinal).build();
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
        CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform, ImmutableList.of(top)));
    assertTrue(bottomInput.getPreprocessorFlags().get(CxxSource.Type.C).contains(sentinal));
    assertFalse(totalInput.getPreprocessorFlags().get(CxxSource.Type.C).contains(sentinal));
  }
}
