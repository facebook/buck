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
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildTargetSourcePathTest {

  private BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule = new FakeBuildRule(BuildRuleType.of("example"), target, pathResolver);
    resolver.addToIndex(rule);
    BuildTargetSourcePath path = new BuildTargetSourcePath(
        projectFilesystem,
        rule.getBuildTarget());

    try {
      pathResolver.getPath(path);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("No known output for: " + target.getFullyQualifiedName(), e.getMessage());
    }
  }

  @Test
  public void mustUseProjectFilesystemToResolvePathToFile() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule = new FakeBuildRule(BuildRuleType.of("example"), target, pathResolver) {
      @Override
      public Path getPathToOutputFile() {
        return Paths.get("cheese");
      }
    };
    resolver.addToIndex(rule);

    BuildTargetSourcePath path = new BuildTargetSourcePath(
        projectFilesystem,
        rule.getBuildTarget());

    Path resolved = pathResolver.getPath(path);

    assertEquals(Paths.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheBuildTarget() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    BuildTargetSourcePath path = new BuildTargetSourcePath(projectFilesystem, target);

    assertEquals(target, path.getTarget());
  }

  @Test
  public void explicitlySetPath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(
        BuildRuleType.of("example"),
        target,
        pathResolver);
    Path path = Paths.get("blah");
    BuildTargetSourcePath buildTargetSourcePath = new BuildTargetSourcePath(
        projectFilesystem,
        rule.getBuildTarget(),
        path);
    assertEquals(target, buildTargetSourcePath.getTarget());
    assertEquals(path, pathResolver.getPath(buildTargetSourcePath));
  }

}
