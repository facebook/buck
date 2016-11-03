/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.graphql;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSortedSet;

public class GraphQlLibraryDescription implements Description<GraphQlLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("graphql_library");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public GraphQlLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new GraphQlLibraryDescription.Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    return new GraphQlLibrary(
        params,
        new SourcePathResolver(resolver),
        args.srcs);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  }
}
