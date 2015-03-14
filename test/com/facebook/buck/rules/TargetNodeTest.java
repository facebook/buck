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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class TargetNodeTest {

  @Test
  public void testIgnoreNonBuildTargetOrPathOrSourcePathArgument()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    Description<Arg> description = new TestDescription();
    BuildRuleFactoryParams buildRuleFactoryParams = buildRuleFactoryParams();
    TargetNode<Arg> targetNode = new TargetNode<>(
        description,
        createPopulatedConstructorArg(
            description,
            buildRuleFactoryParams,
            ImmutableMap.<String, Object>of(
                "deps", ImmutableList.of(),
                "string", "//example/path:one",
                "target", "//example/path:two",
                "sourcePaths", ImmutableSortedSet.of())),
        buildRuleFactoryParams,
        ImmutableSet.<BuildTarget>of(),
        ImmutableSet.<BuildTargetPattern>of());

    assertTrue(targetNode.getExtraDeps().isEmpty());
    assertTrue(targetNode.getDeclaredDeps().isEmpty());
  }

  @Test
  public void testDepsAndPathsAreCollected()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    Description<Arg> description = new TestDescription();
    BuildRuleFactoryParams buildRuleFactoryParams = buildRuleFactoryParams();
    ImmutableList<String> depsStrings = ImmutableList.of(
        "//example/path:one",
        "//example/path:two");
    ImmutableSet<BuildTarget> depsTargets = FluentIterable
        .from(depsStrings)
        .transform(
            new Function<String, BuildTarget>() {
               @Override
               public BuildTarget apply(String input) {
                 return BuildTargetFactory.newInstance(input);
               }
            })
        .toSet();
    TargetNode<Arg> targetNode = new TargetNode<>(
        description,
        createPopulatedConstructorArg(
            description,
            buildRuleFactoryParams,
            ImmutableMap.<String, Object>of(
                "deps", depsStrings,
                "sourcePaths", ImmutableList.of("//example/path:four", "MyClass.java"),
                "appleSource", "//example/path:five",
                "source", "AnotherClass.java")),
        buildRuleFactoryParams,
        depsTargets,
        ImmutableSet.<BuildTargetPattern>of());

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

  public class Arg {
    public ImmutableSortedSet<BuildTarget> deps;
    public ImmutableSortedSet<SourcePath> sourcePaths;
    public Optional<SourceWithFlags> appleSource;
    public Optional<Path> source;
    public Optional<String> string;
    @Hint(isDep = false)
    public Optional<BuildTarget> target;
  }

  public class TestDescription implements Description<Arg> {

    @Override
    public BuildRuleType getBuildRuleType() {
      return BuildRuleType.of("example");
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
      return new FakeBuildRule(params, new SourcePathResolver(resolver));
    }
  }

  public BuildRuleFactoryParams buildRuleFactoryParams() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:three");
    return NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
        parser,
        target);
  }

  public Arg createPopulatedConstructorArg(
      Description<Arg> description,
      BuildRuleFactoryParams buildRuleFactoryParams,
      Map<String, Object> instance) throws NoSuchBuildTargetException {
    ConstructorArgMarshaller marshaller = new ConstructorArgMarshaller();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Arg constructorArg = description.createUnpopulatedConstructorArg();
    try {
      marshaller.populate(
          projectFilesystem,
          buildRuleFactoryParams,
          constructorArg,
          ImmutableSet.<BuildTarget>builder(),
          ImmutableSet.<BuildTargetPattern>builder(),
          instance);
    } catch (ConstructorArgMarshalException e) {
      throw new RuntimeException(e);
    }
    return constructorArg;
  }
}
