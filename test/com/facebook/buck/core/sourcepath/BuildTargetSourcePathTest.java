/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.sourcepath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import java.nio.file.Paths;
import org.junit.Test;

public class BuildTargetSourcePathTest {

  private final BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule rule = new FakeBuildRule(target);
    rule.setOutputFile(null);
    graphBuilder.addToIndex(rule);
    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    try {
      graphBuilder.getSourcePathResolver().getCellUnsafeRelPath(path);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("No known output for: " + target.getFullyQualifiedName(), e.getMessage());
    }
  }

  @Test
  public void mustUseProjectFilesystemToResolvePathToFile() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule =
        new FakeBuildRule(target) {
          @Override
          public SourcePath getSourcePathToOutput() {
            return com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath.of(
                getBuildTarget(), Paths.get("cheese"));
          }
        };
    graphBuilder.addToIndex(rule);

    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    RelPath resolved = graphBuilder.getSourcePathResolver().getCellUnsafeRelPath(path);

    assertEquals(RelPath.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheBuildTarget() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    DefaultBuildTargetSourcePath path = DefaultBuildTargetSourcePath.of(target);

    assertEquals(target, path.getTarget());
  }

  @Test
  public void explicitlySetPath() {
    SourcePathResolverAdapter pathResolver = new TestActionGraphBuilder().getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(target);
    RelPath path = RelPath.get("blah");
    ExplicitBuildTargetSourcePath buildTargetSourcePath =
        com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(), path);
    assertEquals(target, buildTargetSourcePath.getTarget());
    assertEquals(path, pathResolver.getCellUnsafeRelPath(buildTargetSourcePath));
  }

  @Test
  public void explicitlySetSourcePathExplicitTarget() {
    SourcePathResolverAdapter pathResolver = new TestActionGraphBuilder().getSourcePathResolver();
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:baz"));
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:waz"));
    RelPath path = RelPath.get("blah");

    ExplicitBuildTargetSourcePath sourcePath1 =
        com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath.of(
            rule1.getBuildTarget(), path);
    ForwardingBuildTargetSourcePath sourcePath2 =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            rule2.getBuildTarget(), sourcePath1);

    assertEquals(path, pathResolver.getCellUnsafeRelPath(sourcePath1));
    assertEquals(path, pathResolver.getCellUnsafeRelPath(sourcePath2));
  }

  @Test
  public void explicitlySetSourcePathImplicitTarget() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:baz"));
    graphBuilder.addToIndex(rule1);
    RelPath path = RelPath.get("blah");
    rule1.setOutputFile(path.toString());
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:waz"));
    graphBuilder.addToIndex(rule2);

    DefaultBuildTargetSourcePath sourcePath1 =
        DefaultBuildTargetSourcePath.of(rule1.getBuildTarget());
    ForwardingBuildTargetSourcePath sourcePath2 =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            rule2.getBuildTarget(), sourcePath1);

    assertEquals(path, pathResolver.getCellUnsafeRelPath(sourcePath1));
    assertEquals(path, pathResolver.getCellUnsafeRelPath(sourcePath2));
  }

  @Test
  public void explicitlySetSourcePathChainsToPathSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule1"));
    graphBuilder.addToIndex(rule1);
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule2"));
    graphBuilder.addToIndex(rule2);
    FakeBuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule3"));
    graphBuilder.addToIndex(rule3);

    PathSourcePath sourcePath0 = FakeSourcePath.of("boom");
    ForwardingBuildTargetSourcePath sourcePath1 =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            rule1.getBuildTarget(), sourcePath0);
    ForwardingBuildTargetSourcePath sourcePath2 =
        com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath.of(
            rule2.getBuildTarget(), sourcePath1);

    assertEquals(
        pathResolver.getCellUnsafeRelPath(sourcePath0),
        pathResolver.getCellUnsafeRelPath(sourcePath1));
    assertEquals(
        pathResolver.getCellUnsafeRelPath(sourcePath0),
        pathResolver.getCellUnsafeRelPath(sourcePath2));
  }

  @Test
  public void sameBuildTargetsWithDifferentPathsAreDifferent() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(target);
    ExplicitBuildTargetSourcePath path1 =
        com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(), Paths.get("something"));
    ExplicitBuildTargetSourcePath path2 =
        com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(), Paths.get("something else"));
    assertNotEquals(path1, path2);
    assertNotEquals(path1.hashCode(), path2.hashCode());
  }
}
