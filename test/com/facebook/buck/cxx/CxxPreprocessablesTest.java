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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessablesTest {

  private static class FakeCxxPreprocessorDep extends FakeBuildRule
      implements CxxPreprocessorDep {

    private final CxxPreprocessorInput input;

    public FakeCxxPreprocessorDep(BuildRuleParams params, CxxPreprocessorInput input) {
      super(params);
      this.input = Preconditions.checkNotNull(input);
    }

    @Override
    public CxxPreprocessorInput getCxxPreprocessorInput() {
      return input;
    }

  }

  private static FakeCxxPreprocessorDep createFakeCxxPreprocessorDep(
      BuildTarget target,
      CxxPreprocessorInput input,
      BuildRule... deps) {
    return new FakeCxxPreprocessorDep(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        input);
  }

  private static FakeBuildRule createFakeBuildRule(
      BuildTarget target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
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
        target, headerMap);
    assertEquals(expected, actual);
  }

  @Test
  public void getTransitiveCxxPreprocessorInput() {

    // Setup a simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget1 = BuildTargetFactory.newInstance("//:cpp1");
    CxxPreprocessorInput input1 = new CxxPreprocessorInput(
        ImmutableSet.of(cppDepTarget1),
        ImmutableList.of("-Dtest=yes"),
        ImmutableList.of("-Dtest=yes"),
        ImmutableList.of(Paths.get("foo/bar"), Paths.get("hello")),
        ImmutableList.of(Paths.get("/usr/include")));
    BuildTarget depTarget1 = BuildTargetFactory.newInstance("//:dep1");
    FakeCxxPreprocessorDep dep1 = createFakeCxxPreprocessorDep(depTarget1, input1);

    // Setup another simple CxxPreprocessorDep which contributes components to preprocessing.
    BuildTarget cppDepTarget2 = BuildTargetFactory.newInstance("//:cpp2");
    CxxPreprocessorInput input2 = new CxxPreprocessorInput(
        ImmutableSet.of(cppDepTarget2),
        ImmutableList.of("-DBLAH"),
        ImmutableList.of("-DBLAH"),
        ImmutableList.of(Paths.get("goodbye")),
        ImmutableList.of(Paths.get("test")));
    BuildTarget depTarget2 = BuildTargetFactory.newInstance("//:dep2");
    FakeCxxPreprocessorDep dep2 = createFakeCxxPreprocessorDep(depTarget2, input2);

    // Create a normal dep which depends on the two CxxPreprocessorDep rules above.
    BuildTarget depTarget3 = BuildTargetFactory.newInstance("//:dep3");
    FakeBuildRule dep3 = createFakeBuildRule(depTarget3, dep1, dep2);

    // Verify that getTransitiveCxxPreprocessorInput gets all CxxPreprocessorInput objects
    // from the relevant rules above.
    CxxPreprocessorInput expected = CxxPreprocessorInput.concat(
        ImmutableList.of(input1, input2));
    CxxPreprocessorInput actual = CxxPreprocessables.getTransitiveCxxPreprocessorInput(
        ImmutableList.<BuildRule>of(dep3));
    assertEquals(expected, actual);
  }

  @Test
  public void createHeaderSymlinkTreeBuildRuleHasNoDeps() {

    // Setup up the main build target and build params, which some random dep.  We'll make
    // sure the dep doesn't get propagated to the symlink rule below.
    FakeBuildRule dep = createFakeBuildRule(BuildTargetFactory.newInstance("//random:dep"));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    Path root = Paths.get("root");

    // Setup a simple genrule we can wrap in a BuildRuleSourcePath to model a input source
    // that is built by another rule.
    Genrule genrule = GenruleBuilder
        .createGenrule(BuildTargetFactory.newInstance("//:genrule"))
        .setOut("foo/bar.o")
        .build();

    // Setup the link map with both a regular path-based source path and one provided by
    // another build rule.
    ImmutableMap<Path, SourcePath> links = ImmutableMap.<Path, SourcePath>of(
        Paths.get("link1"), new TestSourcePath("hello"),
        Paths.get("link2"), new BuildRuleSourcePath(genrule));

    // Build our symlink tree rule using the helper method.
    SymlinkTree symlinkTree = CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
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
  public void createHeaderBuildRuleHasDepsOnlyFromSourcePaths() {

    // Setup up the main build target and build params, which some random dep.  We'll make
    // sure the dep doesn't get propagated to the headers rule below.
    FakeBuildRule dep = createFakeBuildRule(BuildTargetFactory.newInstance("//random:dep"));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();

    // Setup a simple genrule we can wrap in a BuildRuleSourcePath to model a input source
    // that is built by another rule.
    Genrule genrule = GenruleBuilder
        .createGenrule(BuildTargetFactory.newInstance("//:genrule"))
        .setOut("foo/bar.o")
        .build();

    // Setup the link map with both a regular path-based source path and one provided by
    // another build rule.
    ImmutableMap<Path, SourcePath> links = ImmutableMap.<Path, SourcePath>of(
        Paths.get("link1"), new TestSourcePath("hello"),
        Paths.get("link2"), new BuildRuleSourcePath(genrule));

    // Build our symlink tree rule using the helper method.
    CxxHeader cxxHeader = CxxPreprocessables.createHeaderBuildRule(
        target,
        params,
        links);

    // Make sure that the only dep that makes it into the CxxHeader rule is the genrule above
    // which generates one of the headers.
    assertEquals(ImmutableSortedSet.<BuildRule>of(genrule), cxxHeader.getDeps());
  }

}
