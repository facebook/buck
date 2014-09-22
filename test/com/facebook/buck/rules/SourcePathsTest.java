/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcePathsTest {

  @Test
  public void testEmptyListAsInputToFilterInputsToCompareToOutput() {
    Iterable<SourcePath> sourcePaths = ImmutableList.of();
    Iterable<Path> inputs = SourcePaths.filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(ImmutableList.<String>of(), inputs);
  }

  @Test
  public void testFilterInputsToCompareToOutputExcludesBuildTargetSourcePaths() {
    FakeBuildRule rule = new FakeBuildRule(
        new BuildRuleType("example"),
        BuildTargetFactory.newInstance("//java/com/facebook:facebook"));

    Iterable<? extends SourcePath> sourcePaths = ImmutableList.of(
        new TestSourcePath("java/com/facebook/Main.java"),
        new TestSourcePath("java/com/facebook/BuckConfig.java"),
        new BuildRuleSourcePath(rule));
    Iterable<Path> inputs = SourcePaths.filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(
        "Iteration order should be preserved: results should not be alpha-sorted.",
        ImmutableList.of(
            Paths.get("java/com/facebook/Main.java"),
            Paths.get("java/com/facebook/BuckConfig.java")),
        inputs);
  }

  @Test
  public void getSourcePathNameOnPathSourcePath() {
    Path path = Paths.get("hello/world.txt");

    // Test that constructing a PathSourcePath without an explicit name resolves to the
    // string representation of the path.
    PathSourcePath pathSourcePath1 = new PathSourcePath(path);
    String actual1 = pathSourcePath1.getName();
    assertEquals(path.toString(), actual1);

    // Test that constructing a PathSourcePath *with* an explicit name resolves to that
    // name.
    String explicitName = "blah";
    PathSourcePath pathSourcePath2 = new PathSourcePath(path, explicitName);
    String actual2 = pathSourcePath2.getName();
    assertEquals(explicitName, actual2);
  }

  @Test
  public void getSourcePathNameOnBuildRuleSourcePath() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Verify that wrapping a genrule in a BuildRuleSourcePath resolves to the output name of
    // that genrule.
    String out = "test/blah.txt";
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
        .setOut(out)
        .build(resolver);
    BuildRuleSourcePath buildRuleSourcePath1 = new BuildRuleSourcePath(genrule);
    String actual1 = buildRuleSourcePath1.getName();
    assertEquals(out, actual1);

    // Test that using other BuildRule types resolves to the short name.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(fakeBuildTarget).build());
    BuildRuleSourcePath buildRuleSourcePath2 = new BuildRuleSourcePath(fakeBuildRule);
    String actual2 = buildRuleSourcePath2.getName();
    assertEquals(fakeBuildTarget.getShortNameOnly(), actual2);
  }

  @Test
  public void getSourcePathNamesThrowsOnDuplicates() {
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    String parameter = "srcs";
    PathSourcePath pathSourcePath1 = new PathSourcePath(Paths.get("a"), "same_name");
    PathSourcePath pathSourcePath2 = new PathSourcePath(Paths.get("b"), "same_name");

    // Try to resolve these source paths, with the same name, together and verify that an
    // exception is thrown.
    try {
      SourcePaths.getSourcePathNames(
          target,
          parameter,
          ImmutableList.<SourcePath>of(pathSourcePath1, pathSourcePath2));
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("duplicate entries"));
    }
  }

}
