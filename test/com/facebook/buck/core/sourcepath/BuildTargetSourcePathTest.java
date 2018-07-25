/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.sourcepath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildRule;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class BuildTargetSourcePathTest {

  private BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    FakeBuildRule rule = new FakeBuildRule(target);
    rule.setOutputFile(null);
    graphBuilder.addToIndex(rule);
    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    try {
      pathResolver.getRelativePath(path);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("No known output for: " + target.getFullyQualifiedName(), e.getMessage());
    }
  }

  @Test
  public void mustUseProjectFilesystemToResolvePathToFile() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildRule rule =
        new FakeBuildRule(target) {
          @Override
          public SourcePath getSourcePathToOutput() {
            return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("cheese"));
          }
        };
    graphBuilder.addToIndex(rule);

    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    Path resolved = pathResolver.getRelativePath(path);

    assertEquals(Paths.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheBuildTarget() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(target);

    assertEquals(target, path.getTarget());
  }

  @Test
  public void explicitlySetPath() {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(target);
    Path path = Paths.get("blah");
    ExplicitBuildTargetSourcePath buildTargetSourcePath =
        ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), path);
    assertEquals(target, buildTargetSourcePath.getTarget());
    assertEquals(path, pathResolver.getRelativePath(buildTargetSourcePath));
  }

  @Test
  public void explicitlySetSourcePathExplicitTarget() {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:baz"));
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:waz"));
    Path path = Paths.get("blah");

    ExplicitBuildTargetSourcePath sourcePath1 =
        ExplicitBuildTargetSourcePath.of(rule1.getBuildTarget(), path);
    ForwardingBuildTargetSourcePath sourcePath2 =
        ForwardingBuildTargetSourcePath.of(rule2.getBuildTarget(), sourcePath1);

    assertEquals(path, pathResolver.getRelativePath(sourcePath1));
    assertEquals(path, pathResolver.getRelativePath(sourcePath2));
  }

  @Test
  public void explicitlySetSourcePathImplicitTarget() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:baz"));
    graphBuilder.addToIndex(rule1);
    Path path = Paths.get("blah");
    rule1.setOutputFile(path.toString());
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:waz"));
    graphBuilder.addToIndex(rule2);

    DefaultBuildTargetSourcePath sourcePath1 =
        DefaultBuildTargetSourcePath.of(rule1.getBuildTarget());
    ForwardingBuildTargetSourcePath sourcePath2 =
        ForwardingBuildTargetSourcePath.of(rule2.getBuildTarget(), sourcePath1);

    assertEquals(path, pathResolver.getRelativePath(sourcePath1));
    assertEquals(path, pathResolver.getRelativePath(sourcePath2));
  }

  @Test
  public void explicitlySetSourcePathChainsToPathSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule1"));
    graphBuilder.addToIndex(rule1);
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule2"));
    graphBuilder.addToIndex(rule2);
    FakeBuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule3"));
    graphBuilder.addToIndex(rule3);

    PathSourcePath sourcePath0 = FakeSourcePath.of("boom");
    ForwardingBuildTargetSourcePath sourcePath1 =
        ForwardingBuildTargetSourcePath.of(rule1.getBuildTarget(), sourcePath0);
    ForwardingBuildTargetSourcePath sourcePath2 =
        ForwardingBuildTargetSourcePath.of(rule2.getBuildTarget(), sourcePath1);

    assertEquals(
        pathResolver.getRelativePath(sourcePath0), pathResolver.getRelativePath(sourcePath1));
    assertEquals(
        pathResolver.getRelativePath(sourcePath0), pathResolver.getRelativePath(sourcePath2));
  }

  @Test
  public void sameBuildTargetsWithDifferentPathsAreDifferent() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(target);
    ExplicitBuildTargetSourcePath path1 =
        ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), Paths.get("something"));
    ExplicitBuildTargetSourcePath path2 =
        ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), Paths.get("something else"));
    assertNotEquals(path1, path2);
    assertNotEquals(path1.hashCode(), path2.hashCode());
  }
}
