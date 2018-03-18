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

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.args.StringArg;
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
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        CxxPreprocessorInput input) {
      super(buildTarget, projectFilesystem, params);
      this.input = Preconditions.checkNotNull(input);
    }

    @Override
    public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
        CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
      return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
    }

    @Override
    public CxxPreprocessorInput getCxxPreprocessorInput(
        CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
      return input;
    }

    @Override
    public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
        CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
      ImmutableMap.Builder<BuildTarget, CxxPreprocessorInput> builder = ImmutableMap.builder();
      builder.put(getBuildTarget(), getCxxPreprocessorInput(cxxPlatform, ruleResolver));
      for (BuildRule dep : getBuildDeps()) {
        if (dep instanceof CxxPreprocessorDep) {
          builder.putAll(
              ((CxxPreprocessorDep) dep)
                  .getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver));
        }
      }
      return builder.build();
    }
  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      BuildTarget target, CxxPreprocessorInput input, BuildRule... deps) {
    return new FakeCxxPreprocessorDep(
        target,
        new FakeProjectFilesystem(),
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.copyOf(deps)),
        input);
  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      String target, CxxPreprocessorInput input, BuildRule... deps) {
    return createFakeCxxPreprocessorDep(BuildTargetFactory.newInstance(target), input, deps);
  }

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void resolveHeaderMap() {
    BuildTarget target = BuildTargetFactory.newInstance("//hello/world:test");
    ImmutableMap<String, SourcePath> headerMap =
        ImmutableMap.of(
            "foo/bar.h", FakeSourcePath.of("header1.h"),
            "foo/hello.h", FakeSourcePath.of("header2.h"));

    // Verify that the resolveHeaderMap returns sane results.
    ImmutableMap<Path, SourcePath> expected =
        ImmutableMap.of(
            target.getBasePath().resolve("foo/bar.h"), FakeSourcePath.of("header1.h"),
            target.getBasePath().resolve("foo/hello.h"), FakeSourcePath.of("header2.h"));
    ImmutableMap<Path, SourcePath> actual =
        CxxPreprocessables.resolveHeaderMap(target.getBasePath(), headerMap);
    assertEquals(expected, actual);
  }

  @Test
  public void getTransitiveCxxPreprocessorInput() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(
            new CxxBuckConfig(FakeBuckConfig.builder().setFilesystem(filesystem).build()));

    // Setup a simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget1 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:cpp1");
    CxxPreprocessorInput input1 =
        CxxPreprocessorInput.builder()
            .addRules(cppDepTarget1)
            .putPreprocessorFlags(CxxSource.Type.C, StringArg.of("-Dtest=yes"))
            .putPreprocessorFlags(CxxSource.Type.CXX, StringArg.of("-Dtest=yes"))
            .build();
    BuildTarget depTarget1 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:dep1");
    FakeCxxPreprocessorDep dep1 = createFakeCxxPreprocessorDep(depTarget1, input1);

    // Setup another simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget2 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:cpp2");
    CxxPreprocessorInput input2 =
        CxxPreprocessorInput.builder()
            .addRules(cppDepTarget2)
            .putPreprocessorFlags(CxxSource.Type.C, StringArg.of("-DBLAH"))
            .putPreprocessorFlags(CxxSource.Type.CXX, StringArg.of("-DBLAH"))
            .build();
    BuildTarget depTarget2 = BuildTargetFactory.newInstance("//:dep2");
    FakeCxxPreprocessorDep dep2 = createFakeCxxPreprocessorDep(depTarget2, input2);

    // Create a normal dep which depends on the two CxxPreprocessorDep rules above.
    BuildTarget depTarget3 = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:dep3");
    CxxPreprocessorInput nothing = CxxPreprocessorInput.of();
    FakeCxxPreprocessorDep dep3 = createFakeCxxPreprocessorDep(depTarget3, nothing, dep1, dep2);

    // Verify that getTransitiveCxxPreprocessorInput gets all CxxPreprocessorInput objects
    // from the relevant rules above.
    ImmutableList<CxxPreprocessorInput> expected = ImmutableList.of(nothing, input1, input2);
    ImmutableList<CxxPreprocessorInput> actual =
        ImmutableList.copyOf(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform, new TestBuildRuleResolver(), ImmutableList.<BuildRule>of(dep3)));
    assertEquals(expected, actual);
  }

  @Test
  public void createHeaderSymlinkTreeBuildRuleHasNoDeps() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup up the main build target and build params, which some random dep.  We'll make
    // sure the dep doesn't get propagated to the symlink rule below.
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar");
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
            FakeSourcePath.of("hello"),
            Paths.get("link2"),
            genrule.getSourcePathToOutput());

    // Build our symlink tree rule using the helper method.
    HeaderSymlinkTree symlinkTree =
        CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
            target,
            filesystem,
            new SourcePathRuleFinder(resolver),
            root,
            links,
            HeaderMode.SYMLINK_TREE_ONLY);

    // Verify that the symlink tree has no deps.  This is by design, since setting symlinks can
    // be done completely independently from building the source that the links point to and
    // independently from the original deps attached to the input build rule params.
    assertTrue(symlinkTree.getBuildDeps().isEmpty());
  }

  @Test
  public void getTransitiveNativeLinkableInputDoesNotTraversePastNonNativeLinkables() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Create a native linkable that sits at the bottom of the dep chain.
    StringArg sentinal = StringArg.of("bottom");
    CxxPreprocessorInput bottomInput =
        CxxPreprocessorInput.builder().putPreprocessorFlags(CxxSource.Type.C, sentinal).build();
    BuildRule bottom = createFakeCxxPreprocessorDep("//:bottom", bottomInput);

    // Create a non-native linkable that sits in the middle of the dep chain, preventing
    // traversals to the bottom native linkable.
    BuildRule middle = new FakeBuildRule("//:middle", bottom);

    // Create a native linkable that sits at the top of the dep chain.
    CxxPreprocessorInput topInput = CxxPreprocessorInput.of();
    BuildRule top = createFakeCxxPreprocessorDep("//:top", topInput, middle);

    // Now grab all input via traversing deps and verify that the middle rule prevents pulling
    // in the bottom input.
    CxxPreprocessorInput totalInput =
        CxxPreprocessorInput.concat(
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform, new TestBuildRuleResolver(), ImmutableList.of(top)));
    assertTrue(bottomInput.getPreprocessorFlags().get(CxxSource.Type.C).contains(sentinal));
    assertFalse(totalInput.getPreprocessorFlags().get(CxxSource.Type.C).contains(sentinal));
  }
}
