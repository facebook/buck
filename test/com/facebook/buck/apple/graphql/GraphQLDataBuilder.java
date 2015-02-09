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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Paths;
import java.util.Map;

public class GraphQLDataBuilder extends AbstractNodeBuilder<GraphQLDataDescription.Arg> {

  protected GraphQLDataBuilder(BuildTarget target) {
    super(
        new GraphQLDataDescription(
            new GraphQLConfig(
                new FakeBuckConfig(
                    ImmutableMap.<String, Map<String, String>>of(
                        "graphql",
                        ImmutableMap.of("model-generator", "model-generator")),
                    new FakeProjectFilesystem(ImmutableSet.of(Paths.get("model-generator")))))),
        target);
  }

  public static GraphQLDataBuilder createBuilder(BuildTarget target) {
    return new GraphQLDataBuilder(target);
  }

  public GraphQLDataBuilder setQueries(ImmutableSortedSet<SourcePath> queries) {
    arg.queries = queries;
    return this;
  }

  @Override
  public TargetNode<GraphQLDataDescription.Arg> build() {
    if (arg.consistencyConfig == null) {
      arg.consistencyConfig = new PathSourcePath(Paths.get(""));
    }
    if (arg.clientSchemaConfig == null) {
      arg.clientSchemaConfig = new PathSourcePath(Paths.get(""));
    }
    if (arg.schema == null) {
      arg.schema = new PathSourcePath(Paths.get(""));
    }
    if (arg.mutations == null) {
      arg.mutations = new PathSourcePath(Paths.get(""));
    }
    if (arg.modelTags == null) {
      arg.modelTags = ImmutableSet.of();
    }
    if (arg.knownIssuesFile == null) {
      arg.knownIssuesFile = new PathSourcePath(Paths.get(""));
    }
    if (arg.persistIds == null) {
      arg.persistIds = new PathSourcePath(Paths.get(""));
    }
    return super.build();
  }
}
