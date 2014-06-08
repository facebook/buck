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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * A {@link TargetNode} represents a node in the target graph which is created by the
 * {@link com.facebook.buck.parser.Parser} as a result of parsing BUCK files in a project. It is
 * responsible for processing the raw (python) inputs of a build rule, and gathering any build
 * targets and paths referenced from those inputs.
 */
public class TargetNode<T extends ConstructorArg> {

  private final BuildRuleFactoryParams ruleFactoryParams;
  private final Description<T> description;

  private final Set<Path> pathsReferenced;
  private final ImmutableSortedSet<BuildTarget> declaredDeps;
  private final ImmutableSortedSet<BuildTarget> extraDeps;

  @VisibleForTesting
  TargetNode(
      Description<T> description,
      BuildRuleFactoryParams params,
      Set<BuildTarget> declaredDeps) {
    this.description = Preconditions.checkNotNull(description);
    this.ruleFactoryParams = Preconditions.checkNotNull(params);
    this.pathsReferenced = ImmutableSet.of();
    this.declaredDeps = ImmutableSortedSet.copyOf(declaredDeps);
    this.extraDeps = ImmutableSortedSet.of();
  }

  public TargetNode(Description<T> description, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    this.description = Preconditions.checkNotNull(description);
    this.ruleFactoryParams = Preconditions.checkNotNull(params);

    final ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    final ImmutableSortedSet.Builder<BuildTarget> extraDeps = ImmutableSortedSet.naturalOrder();
    final ImmutableSortedSet.Builder<BuildTarget> declaredDeps = ImmutableSortedSet.naturalOrder();

    for (String rawDep : params.getOptionalListAttribute("deps")) {
      BuildTarget target = params.resolveBuildTarget(rawDep);
      declaredDeps.add(Preconditions.checkNotNull(target));
    }
    this.declaredDeps = declaredDeps.build();

    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    TypeCoercerFactory typeCoercerFactory = new TypeCoercerFactory();
    T arg = description.createUnpopulatedConstructorArg();
    for (Field field : arg.getClass().getFields()) {
      ParamInfo info =
          new ParamInfo(typeCoercerFactory, Paths.get(params.target.getBasePath()), field);
      if (info.hasElementTypes(BuildRule.class, SourcePath.class, Path.class)) {
        detectBuildTargetsAndPathsForParameter(extraDeps, paths, info, params);
      }
    }

    if (description instanceof ImplicitDepsInferringDescription) {
      Iterable<String> rawTargets =
          ((ImplicitDepsInferringDescription) description).findDepsFromParams(params);
      for (String rawTarget : rawTargets) {
        if (isPossiblyATarget(rawTarget)) {
          extraDeps.add(params.resolveBuildTarget(rawTarget));
        }
      }
    }

    this.extraDeps = ImmutableSortedSet.copyOf(
        Sets.difference(extraDeps.build(), this.declaredDeps));
    this.pathsReferenced = paths.build();
  }

  public Description<T> getDescription() {
    return description;
  }

  public BuildTarget getBuildTarget() {
    return ruleFactoryParams.target;
  }

  public Set<Path> getInputs() {
    return pathsReferenced;
  }

  public Set<BuildTarget> getDeclaredDeps() {
    return declaredDeps;
  }

  public Set<BuildTarget> getExtraDeps() {
    return extraDeps;
  }

  public Set<BuildTarget> getDeps() {
    return Sets.union(declaredDeps, extraDeps);
  }

  public BuildRuleFactoryParams getRuleFactoryParams() {
    return ruleFactoryParams;
  }

  private void detectBuildTargetsAndPathsForParameter(
      final ImmutableSet.Builder<BuildTarget> depsBuilder,
      final ImmutableSet.Builder<Path> pathsBuilder,
      ParamInfo info,
      final BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(
          new ParamInfo.Traversal() {
            @Override
            public void traverse(Object object) {
              if (object instanceof String) {
                try {
                  addTargetOrPathIfPresent(depsBuilder, pathsBuilder, params, (String) object);
                } catch (NoSuchBuildTargetException e) {
                  throw new RuntimeException(e);
                }
              }
            }
          },
          params.getNullableRawAttribute(info.getName()));
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchBuildTargetException) {
        throw (NoSuchBuildTargetException) e.getCause();
      }
    }
  }

  private void addTargetOrPathIfPresent(
      ImmutableSet.Builder<BuildTarget> targets,
      ImmutableSet.Builder<Path> paths,
      BuildRuleFactoryParams params,
      String param) throws NoSuchBuildTargetException {
    if (isPossiblyATarget(param)) {
      targets.add(params.resolveBuildTarget(param));
    } else {
      paths.add(params.resolveFilePathRelativeToBuildFileDirectory(param));
    }
  }

  private boolean isPossiblyATarget(String param) {
    return param.charAt(0) == ':' || param.startsWith(BuildTarget.BUILD_TARGET_PREFIX);
  }

}
