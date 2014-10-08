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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.HumanReadableException;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildRuleSourcePathTest {

  private BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test(expected = NullPointerException.class)
  public void requiresBuildTargetToNotBeNull() {
    new BuildRuleSourcePath(null);
  }

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new FakeBuildRule(new BuildRuleType("example"), target, pathResolver);
    BuildRuleSourcePath path = new BuildRuleSourcePath(rule);

    try {
      pathResolver.getPath(path);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("No known output for: " + target.getFullyQualifiedName(), e.getMessage());
    }
  }

  @Test
  public void mustUseProjectFilesystemToResolvePathToFile() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new FakeBuildRule(new BuildRuleType("example"), target, pathResolver) {
      @Override
      public Path getPathToOutputFile() {
        return Paths.get("cheese");
      }
    };

    BuildRuleSourcePath path = new BuildRuleSourcePath(rule);

    Path resolved = pathResolver.getPath(path);

    assertEquals(Paths.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheBuildRuleAsTheReference() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(
        new BuildRuleType("example"),
        target,
        new SourcePathResolver(new BuildRuleResolver()));

    BuildRuleSourcePath path = new BuildRuleSourcePath(rule);

    assertEquals(rule, path.asReference());
  }

  @Test
  public void explicitlySetPath() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(new BuildRuleType("example"), target, pathResolver);
    Path path = Paths.get("blah");
    BuildRuleSourcePath buildRuleSourcePath = new BuildRuleSourcePath(rule, path);
    assertEquals(rule, buildRuleSourcePath.asReference());
    assertEquals(path, pathResolver.getPath(buildRuleSourcePath));
  }

}
