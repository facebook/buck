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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.model.BuildTargetFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ExplicitBuildTargetSourcePathTest {

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
