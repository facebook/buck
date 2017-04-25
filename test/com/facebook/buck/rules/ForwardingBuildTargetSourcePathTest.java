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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class ForwardingBuildTargetSourcePathTest {

  private BuildRuleResolver resolver;
  private SourcePathResolver pathResolver;

  @Before
  public void setUp() {
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
  }

  @Test
  public void forwardsToPathSourcePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//package:name");
    Path relativePath = Paths.get("foo/bar");
    ForwardingBuildTargetSourcePath sourcePath =
        new ForwardingBuildTargetSourcePath(
            target, new PathSourcePath(new FakeProjectFilesystem(), relativePath));
    assertEquals(target, sourcePath.getTarget());
    assertEquals(relativePath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void forwardsToDefaultBuildTargetSourcePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//package:name");
    FakeBuildRule rule = new FakeBuildRule(target, pathResolver);
    rule.setOutputFile("foo/bar");
    resolver.addToIndex(rule);
    ForwardingBuildTargetSourcePath sourcePath =
        new ForwardingBuildTargetSourcePath(target, new DefaultBuildTargetSourcePath(target));
    assertEquals(target, sourcePath.getTarget());
    assertEquals(rule.getOutputFile(), pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void forwardsToExplicitBuildTargetSourcePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//package:name");
    FakeBuildRule rule = new FakeBuildRule(target, pathResolver);
    resolver.addToIndex(rule);
    Path relativePath = Paths.get("foo/bar");
    ForwardingBuildTargetSourcePath sourcePath =
        new ForwardingBuildTargetSourcePath(
            target, new ExplicitBuildTargetSourcePath(target, relativePath));
    assertEquals(target, sourcePath.getTarget());
    assertEquals(relativePath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void chains() {
    BuildTarget target1 = BuildTargetFactory.newInstance("//package:name");
    FakeBuildRule rule1 = new FakeBuildRule(target1, pathResolver);
    resolver.addToIndex(rule1);

    BuildTarget target2 = BuildTargetFactory.newInstance("//package2:name2");
    FakeBuildRule rule2 = new FakeBuildRule(target2, pathResolver);
    resolver.addToIndex(rule2);

    Path relativePath = Paths.get("foo/bar");

    ForwardingBuildTargetSourcePath sourcePath =
        new ForwardingBuildTargetSourcePath(
            target1,
            new ForwardingBuildTargetSourcePath(
                target2, new ExplicitBuildTargetSourcePath(target2, relativePath)));
    assertEquals(target1, sourcePath.getTarget());
    assertEquals(relativePath, pathResolver.getRelativePath(sourcePath));
  }
}
