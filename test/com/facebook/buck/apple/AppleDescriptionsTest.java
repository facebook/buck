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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.apple.graphql.GraphQLDataBuilder;
import com.facebook.buck.apple.graphql.GraphQLDataDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AppleDescriptionsTest {

  @Test
  public void populateModelDependenciesShouldAddEntries() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:library");
    BuildTarget resourceTarget = BuildTargetFactory.newInstance("//:resource");
    BuildTarget modelTarget = BuildTargetFactory.newInstance("//:model");

    TargetNode<AppleNativeTargetDescriptionArg> binary = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget, modelTarget)))
        .build();
    TargetNode<AppleResourceDescription.Arg> resource = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .build();
    TargetNode<GraphQLDataDescription.Arg> model = GraphQLDataBuilder
        .createBuilder(modelTarget)
        .build();

    TargetGraph graph =
        TargetGraphFactory.newInstance(ImmutableSet.of(binary, library, resource, model));

    assertEquals(
        ImmutableMap.of(
            binaryTarget, ImmutableSet.of(model),
            libraryTarget, ImmutableSet.of(model)),
        AppleDescriptions.getTargetsToTransitiveModelDependencies(graph));
  }

  @Test
  public void populateModelDependenciesShouldListMultipleModels() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    BuildTarget libraryTargetA = BuildTargetFactory.newInstance("//:libraryA");
    BuildTarget modelTargetA = BuildTargetFactory.newInstance("//:modelA");
    BuildTarget libraryTargetB = BuildTargetFactory.newInstance("//:libraryB");
    BuildTarget modelTargetB = BuildTargetFactory.newInstance("//:modelB");
    BuildTarget modelTargetC = BuildTargetFactory.newInstance("//:modelC");
    BuildTarget modelTargetD = BuildTargetFactory.newInstance("//:modelD");

    TargetNode<AppleNativeTargetDescriptionArg> binary = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTargetA, libraryTargetB, modelTargetD)))
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> libraryA = AppleLibraryBuilder
        .createBuilder(libraryTargetA)
        .setDeps(Optional.of(ImmutableSortedSet.of(modelTargetA, modelTargetC)))
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> libraryB = AppleLibraryBuilder
        .createBuilder(libraryTargetB)
        .setDeps(Optional.of(ImmutableSortedSet.of(modelTargetB, modelTargetC)))
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelA = GraphQLDataBuilder
        .createBuilder(modelTargetA)
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelB = GraphQLDataBuilder
        .createBuilder(modelTargetB)
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelC = GraphQLDataBuilder
        .createBuilder(modelTargetC)
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelD = GraphQLDataBuilder
        .createBuilder(modelTargetD)
        .build();

    TargetGraph graph = TargetGraphFactory.newInstance(
        ImmutableSet.<TargetNode<?>>of(binary, libraryA, libraryB, modelA, modelB, modelC, modelD));

    assertEquals(
        ImmutableMap.of(
            binaryTarget, ImmutableSet.of(modelA, modelB, modelC, modelD),
            libraryTargetA, ImmutableSet.of(modelA, modelC),
            libraryTargetB, ImmutableSet.of(modelB, modelC)),
        AppleDescriptions.getTargetsToTransitiveModelDependencies(graph));
  }

  @Test
  public void testMergedBuildTarget() {
    BuildTarget modelTargetA = BuildTargetFactory.newInstance("//:modelA.1");
    BuildTarget modelTargetB = BuildTargetFactory.newInstance("//path/to:model-B");
    BuildTarget modelTargetC = BuildTargetFactory.newInstance("//:modelC");
    BuildTarget modelTargetD = BuildTargetFactory.newInstance("//path:modelD");

    assertEquals(
        BuildTargetFactory.newInstance(
            "//buck/synthesized/ios:---modelA-1----modelC---path-to-model-B---path-modelD"),
        AppleDescriptions.getMergedBuildTarget(
            ImmutableSet.of(modelTargetA, modelTargetB, modelTargetC, modelTargetD)));
  }

  @Test
  public void testMergedModels() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    BuildTarget libraryTargetA = BuildTargetFactory.newInstance("//:libraryA");
    BuildTarget modelTargetA = BuildTargetFactory.newInstance("//:modelA");
    BuildTarget libraryTargetB = BuildTargetFactory.newInstance("//:libraryB");
    BuildTarget modelTargetB = BuildTargetFactory.newInstance("//:modelB");
    BuildTarget modelTargetC = BuildTargetFactory.newInstance("//:modelC");
    BuildTarget modelTargetD = BuildTargetFactory.newInstance("//:modelD");

    SourcePath queryA = new PathSourcePath(Paths.get("queryA.graphql"));
    SourcePath queryB = new PathSourcePath(Paths.get("queryB.graphql"));
    SourcePath queryC = new PathSourcePath(Paths.get("queryC.graphql"));
    SourcePath queryD = new PathSourcePath(Paths.get("queryD.graphql"));

    TargetNode<GraphQLDataDescription.Arg> modelA = GraphQLDataBuilder
        .createBuilder(modelTargetA)
        .setQueries(ImmutableSortedSet.of(queryA))
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelB = GraphQLDataBuilder
        .createBuilder(modelTargetB)
        .setQueries(ImmutableSortedSet.of(queryB))
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelC = GraphQLDataBuilder
        .createBuilder(modelTargetC)
        .setQueries(ImmutableSortedSet.of(queryA, queryB, queryC))
        .build();
    TargetNode<GraphQLDataDescription.Arg> modelD = GraphQLDataBuilder
        .createBuilder(modelTargetD)
        .setQueries(ImmutableSortedSet.of(queryD))
        .build();

    ImmutableMap<BuildTarget, TargetNode<GraphQLDataDescription.Arg>> mergedGraphQLModels =
        AppleDescriptions.mergeGraphQLModels(
            ImmutableMap.of(
                binaryTarget, ImmutableSet.of(modelA, modelB, modelC, modelD),
                libraryTargetA, ImmutableSet.of(modelA, modelC),
                libraryTargetB, ImmutableSet.of(modelB, modelC)));

    assertEquals(
        ImmutableMap.of(
            binaryTarget,
            GraphQLDataBuilder
                .createBuilder(
                    AppleDescriptions.getMergedBuildTarget(
                        ImmutableSet.of(modelA, modelB, modelC, modelD)))
                .setQueries(ImmutableSortedSet.of(queryA, queryB, queryC, queryD))
                .build(),
            libraryTargetA,
            GraphQLDataBuilder
                .createBuilder(
                    AppleDescriptions.getMergedBuildTarget(ImmutableSet.of(modelA, modelC)))
                .setQueries(ImmutableSortedSet.of(queryA, queryC))
                .build(),
            libraryTargetB,
            GraphQLDataBuilder
                .createBuilder(
                    AppleDescriptions.getMergedBuildTarget(ImmutableSet.of(modelB, modelC)))
                .setQueries(ImmutableSortedSet.of(queryB, queryC))
                .build())
        ,
        mergedGraphQLModels);
  }

  @Test
  public void getSubgraphWithMergedModelsPicksUpDependencies() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:library");
    BuildTarget modelTarget = BuildTargetFactory.newInstance("//:model");
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule");

    TargetNode<AppleNativeTargetDescriptionArg> binary = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();
    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(modelTarget)))
        .build();
    TargetNode<GraphQLDataDescription.Arg> model = GraphQLDataBuilder
        .createBuilder(modelTarget)
        .setQueries(ImmutableSortedSet.<SourcePath>of(new BuildTargetSourcePath(genruleTarget)))
        .build();
    TargetNode<GenruleDescription.Arg> genrule = GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .build();

    TargetGraph graph =
        TargetGraphFactory.newInstance(ImmutableSet.of(binary, library, model, genrule));

    TargetNode<GraphQLDataDescription.Arg> mergedModel = AppleDescriptions
        .mergeGraphQLModels(ImmutableSet.of(model));

    TargetGraph subgraph = AppleDescriptions
        .getSubgraphWithMergedModels(graph, ImmutableSet.of(mergedModel));

    assertEquals(
        TargetGraphFactory.newInstance(ImmutableSet.of(mergedModel, genrule)),
        subgraph);
  }

}
