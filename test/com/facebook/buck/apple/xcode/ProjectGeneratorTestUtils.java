/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFrameworksBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.RuleMap;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;

final class ProjectGeneratorTestUtils {

  /**
   * Utility class should not be instantiated.
   */
  private ProjectGeneratorTestUtils() {}

  public static <T extends ConstructorArg> BuildRule createBuildRuleWithDefaults(
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      Description<T> description) {
    return createBuildRuleWithDefaults(target, deps, description, Functions.<T>identity());
  }


  /**
   * Helper function to create a build rule for a description, initializing fields to empty values,
   * and allowing a user to override specific fields.
   */
  public static <T extends ConstructorArg> BuildRule createBuildRuleWithDefaults(
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      Description<T> description,
      Function<T, T> overrides) {
    T arg = description.createUnpopulatedConstructorArg();
    for (Field field : arg.getClass().getFields()) {
      Object value;
      if (field.getType().isAssignableFrom(ImmutableSortedSet.class)) {
        value = ImmutableSortedSet.of();
      } else if (field.getType().isAssignableFrom(ImmutableList.class)) {
        value = ImmutableList.of();
      } else if (field.getType().isAssignableFrom(ImmutableMap.class)) {
        value = ImmutableMap.of();
      } else if (field.getType().isAssignableFrom(Optional.class)) {
        value = Optional.absent();
      } else if (field.getType().isAssignableFrom(String.class)) {
        value = "";
      } else if (field.getType().isAssignableFrom(Path.class)) {
        value = Paths.get("");
      } else if (field.getType().isAssignableFrom(SourcePath.class)) {
        value = new PathSourcePath(Paths.get(""));
      } else if (field.getType().isPrimitive()) {
        // do nothing, these are initialized with a zero value
        continue;
      } else {
        // user should provide
        continue;
      }
      try {
        field.set(arg, value);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(target)
        .setDeps(deps)
        .setType(description.getBuildRuleType())
        .build();
    return description.createBuildRule(
        buildRuleParams,
        new BuildRuleResolver(),
        overrides.apply(arg));
  }

  public static PartialGraph createPartialGraphFromBuildRuleResolver(BuildRuleResolver resolver) {
    ActionGraph graph = RuleMap.createGraphFromBuildRules(resolver);
    ImmutableList.Builder<BuildTarget> targets = ImmutableList.builder();
    for (BuildRule rule : graph.getNodes()) {
      targets.add(rule.getBuildTarget());
    }
    return PartialGraphFactory.newInstance(graph, targets.build());
  }

  public static PartialGraph createPartialGraphFromBuildRules(ImmutableSet<BuildRule> rules) {
    return createPartialGraphFromBuildRuleResolver(new BuildRuleResolver(rules));
  }

  static PBXTarget assertTargetExistsAndReturnTarget(
      PBXProject generatedProject,
      String name) {
    for (PBXTarget target : generatedProject.getTargets()) {
      if (target.getName().equals(name)) {
        return target;
      }
    }
    fail("No generated target with name: " + name);
    return null;
  }

  public static void assertHasSingletonFrameworksPhaseWithFrameworkEntries(
      PBXTarget target, ImmutableList<String> frameworks) {
    PBXFrameworksBuildPhase buildPhase =
        getSingletonPhaseByType(target, PBXFrameworksBuildPhase.class);
    assertEquals("Framework phase should have right number of elements",
        frameworks.size(), buildPhase.getFiles().size());

    for (PBXBuildFile file : buildPhase.getFiles()) {
      PBXReference.SourceTree sourceTree = file.getFileRef().getSourceTree();
      switch (sourceTree) {
        case GROUP:
          fail("Should not emit frameworks with sourceTree <group>");
          break;
        case ABSOLUTE:
          fail("Should not emit frameworks with sourceTree <absolute>");
          break;
        // $CASES-OMITTED$
        default:
          String serialized = "$" + sourceTree + "/" + file.getFileRef().getPath();
          assertTrue(
              "Framework should be listed in list of expected frameworks: " + serialized,
              frameworks.contains(serialized));
          break;
      }
    }
  }

  public static <T extends PBXBuildPhase> T getSingletonPhaseByType(
      PBXTarget target, final Class<T> cls) {
    Iterable<PBXBuildPhase> buildPhases =
        Iterables.filter(
            target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
          @Override
          public boolean apply(PBXBuildPhase input) {
            return cls.isInstance(input);
          }
        });
    assertEquals("Build phase should be singleton", 1, Iterables.size(buildPhases));
    @SuppressWarnings("unchecked")
    T element = (T) Iterables.getOnlyElement(buildPhases);
    return element;
  }
}
