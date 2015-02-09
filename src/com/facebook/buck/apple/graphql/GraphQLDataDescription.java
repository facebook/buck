/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple.graphql;

import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.Collections;

public class GraphQLDataDescription
    implements
    Description<GraphQLDataDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<GraphQLDataDescription.Arg> {

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("graphql_data");

  private final Supplier<SourcePath> modelGenerator;
  public static final ImmutableSet<ImmutableSet<Flavor>> VALID_FLAVOR_SETS = ImmutableSet.of(
      ImmutableSet.<Flavor>of(ImmutableFlavor.of(GraphQLGenerationMode.FOR_COMPILING.toString())),
      ImmutableSet.<Flavor>of(ImmutableFlavor.of(GraphQLGenerationMode.FOR_LINKING.toString())));

  public GraphQLDataDescription(GraphQLConfig config) {
    this.modelGenerator = config.getGraphQLModelGenerator();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return VALID_FLAVOR_SETS.contains(flavors);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new GraphQLData(
        params,
        new SourcePathResolver(resolver),
        params.getPathAbsolutifier(),
        modelGenerator.get(),
        params
            .getBuildTarget()
            .getFlavors()
            .contains(ImmutableFlavor.of(GraphQLGenerationMode.FOR_COMPILING.toString())) ?
            GraphQLGenerationMode.FOR_COMPILING :
            GraphQLGenerationMode.FOR_LINKING,
        args.queries,
        args.consistencyConfig,
        args.clientSchemaConfig,
        args.schema,
        args.mutations,
        args.modelTags,
        args.knownIssuesFile,
        args.persistIds);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget, Arg constructorArg) {
    return SourcePaths.filterBuildTargetSourcePaths(Collections.singleton(modelGenerator.get()));
  }

  @SuppressFieldNotInitialized
  public class Arg extends AppleNativeTargetDescriptionArg {
    public ImmutableSet<SourcePath> queries;
    public SourcePath consistencyConfig;
    public SourcePath clientSchemaConfig;
    public SourcePath schema;
    public SourcePath mutations;
    public ImmutableSet<GraphQLModelTag> modelTags;
    public SourcePath knownIssuesFile;
    public SourcePath persistIds;

    public boolean isMergeableWith(Arg other) {
      return this.configs.equals(other.configs) &&
          this.consistencyConfig.equals(other.consistencyConfig) &&
          this.clientSchemaConfig.equals(other.clientSchemaConfig) &&
          this.schema.equals(other.schema) &&
          this.mutations.equals(other.mutations) &&
          this.modelTags.equals(other.modelTags) &&
          this.knownIssuesFile.equals(other.knownIssuesFile) &&
          this.persistIds.equals(other.persistIds) &&
          this.configs.equals(other.configs) &&
          this.srcs.equals(other.srcs) &&
          this.frameworks.equals(other.frameworks) &&
          this.deps.equals(other.deps) &&
          this.gid.equals(other.gid) &&
          this.headerPathPrefix.equals(other.headerPathPrefix) &&
          this.useBuckHeaderMaps.equals(other.useBuckHeaderMaps) &&
          this.prefixHeader.equals(other.prefixHeader) &&
          this.tests.equals(other.tests);
    }

    public Arg mergeWith(Arg other) {
      this.queries = ImmutableSet.copyOf(Sets.union(this.queries, other.queries));
      return this;
    }
  }
}
