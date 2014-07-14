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

package com.facebook.buck.rules;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class TargetNodeTest {

  @Test
  public void testIgnoreNonBuildRuleOrPathOrSourcePathArgument() throws NoSuchBuildTargetException {
    Description<Arg> description = new TestDescription();
    TargetNode<Arg> targetNode = new TargetNode<>(
        description,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
                "string", "//example/path:one",
                "target", "//example/path:two")));

    assertTrue(targetNode.getExtraDeps().isEmpty());
    assertTrue(targetNode.getDeclaredDeps().isEmpty());
  }

  @Test
  public void testDepsAndPathsAreCollected() throws NoSuchBuildTargetException {
    Description<Arg> description = new TestDescription();
    TargetNode<Arg> targetNode = new TargetNode<>(
        description,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
                "deps", ImmutableList.of("//example/path:one", "//example/path:two"),
                "sourcePaths", ImmutableList.of("//example/path:four", "MyClass.java"),
                "appleSource", "//example/path:five",
                "source", "AnotherClass.java")));

    assertThat(
        targetNode.getInputs(),
        containsInAnyOrder(
            Paths.get("example/path/MyClass.java"),
            Paths.get("example/path/AnotherClass.java")));

    assertThat(
        targetNode.getExtraDeps(),
        containsInAnyOrder(
            BuildTargetFactory.newInstance("//example/path:four"),
            BuildTargetFactory.newInstance("//example/path:five")));

    assertThat(
        targetNode.getDeclaredDeps(),
        containsInAnyOrder(
            BuildTargetFactory.newInstance("//example/path:one"),
            BuildTargetFactory.newInstance("//example/path:two")));
  }

  @SuppressWarnings("unused")
  public class Arg implements ConstructorArg {
    public ImmutableSortedSet<BuildRule> deps;
    public ImmutableSortedSet<SourcePath> sourcePaths;
    public Optional<AppleSource> appleSource;
    public Optional<Path> source;
    public Optional<String> string;
    public Optional<BuildTarget> target;
  }

  public class TestDescription implements Description<Arg> {

    @Override
    public BuildRuleType getBuildRuleType() {
      return new BuildRuleType("example");
    }

    @Override
    public Arg createUnpopulatedConstructorArg() {
      return new Arg();
    }

    @Override
    public <A extends Arg> BuildRule createBuildRule(
        BuildRuleParams params,
        BuildRuleResolver resolver,
        A args) {
      return new FakeBuildRule(params);
    }
  }

  public BuildRuleFactoryParams buildRuleFactoryParams(Map<String, Object> args) {
    // TODO(natthu): Add a `isForgiving` flag to FakeProjectFilesystem to mimic the following
    // behaviour.
    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get(".")) {
      @Override
      public boolean exists(Path pathRelativeToProjectRoot) {
        return true;
      }
    };
    BuildTargetParser parser = new BuildTargetParser(filesystem);
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:three");
    return NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
        args, parser, target);
  }
}
