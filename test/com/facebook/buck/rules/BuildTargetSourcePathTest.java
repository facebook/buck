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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.HumanReadableException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class BuildTargetSourcePathTest {

  private BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    FakeBuildRule rule = new FakeBuildRule(target);
    rule.setOutputFile(null);
    resolver.addToIndex(rule);
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
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    BuildRule rule =
        new FakeBuildRule(target) {
          @Override
          public SourcePath getSourcePathToOutput() {
            return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("cheese"));
          }
        };
    resolver.addToIndex(rule);

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
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestBuildRuleResolver()));
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
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestBuildRuleResolver()));
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
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:baz"));
    resolver.addToIndex(rule1);
    Path path = Paths.get("blah");
    rule1.setOutputFile(path.toString());
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:waz"));
    resolver.addToIndex(rule2);

    DefaultBuildTargetSourcePath sourcePath1 =
        DefaultBuildTargetSourcePath.of(rule1.getBuildTarget());
    ForwardingBuildTargetSourcePath sourcePath2 =
        ForwardingBuildTargetSourcePath.of(rule2.getBuildTarget(), sourcePath1);

    assertEquals(path, pathResolver.getRelativePath(sourcePath1));
    assertEquals(path, pathResolver.getRelativePath(sourcePath2));
  }

  @Test
  public void explicitlySetSourcePathChainsToPathSourcePath() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule1"));
    resolver.addToIndex(rule1);
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule2"));
    resolver.addToIndex(rule2);
    FakeBuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//foo/bar:rule3"));
    resolver.addToIndex(rule3);

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
