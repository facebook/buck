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

package com.facebook.buck.android;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class AndroidManifestTest {

  public static final String MANIFEST_TARGET = "//java/com/example:manifest";

  @Test
  public void testSimpleObserverMethods() {
    AndroidManifest androidManifest = createSimpleAndroidManifestRule();

    assertEquals(
        BuildTargets.getGenPath(
            new FakeProjectFilesystem(),
            BuildTargetFactory.newInstance(MANIFEST_TARGET),
            "AndroidManifest__%s__.xml"),
        androidManifest.getPathToOutput());
  }

  @Test
  public void testBuildInternal() throws IOException {
    AndroidManifest androidManifest = createSimpleAndroidManifestRule();

    ProjectFilesystem filesystem = androidManifest.getProjectFilesystem();

    Path skeletonPath = Paths.get("java/com/example/AndroidManifestSkeleton.xml");

    // Mock out a BuildContext whose DependencyGraph will be traversed.
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;

    expect(
        buildContext.getSourcePathResolver().getAbsolutePath(
            new PathSourcePath(filesystem, skeletonPath)))
        .andStubReturn(filesystem.resolve(skeletonPath));

    expect(buildContext.getSourcePathResolver().getAllAbsolutePaths(ImmutableSortedSet.of()))
        .andStubReturn(ImmutableSortedSet.of());

    replay(buildContext.getSourcePathResolver());

    List<Step> steps = androidManifest.getBuildSteps(buildContext, new FakeBuildableContext());
    Step generateManifestStep = steps.get(2);

    assertEquals(
        new GenerateManifestStep(
            filesystem,
            filesystem.resolve(skeletonPath),
            /* libraryManifestPaths */ ImmutableSet.of(),
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(MANIFEST_TARGET),
                "AndroidManifest__%s__.xml")),
        generateManifestStep);
  }

  @Test
  public void testGetTypeMethodOfBuilder() {
    assertEquals(
        "android_manifest",
        Description.getBuildRuleType(AndroidManifestDescription.class).getName());
  }

  private AndroidManifest createSimpleAndroidManifestRule() {
    // First, create the AndroidManifest object.
    BuildRuleParams buildRuleParams =
        new FakeBuildRuleParamsBuilder(MANIFEST_TARGET).build();
    AndroidManifestDescription description = new AndroidManifestDescription();
    AndroidManifestDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.skeleton = new FakeSourcePath("java/com/example/AndroidManifestSkeleton.xml");
    arg.deps = ImmutableSortedSet.<BuildTarget>of();
    return description.createBuildRule(
        TargetGraph.EMPTY,
        buildRuleParams,
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()),
        arg);
  }

  // TODO(abhi): Add another unit test that passes in a non-trivial DependencyGraph and verify that
  // the resulting set of libraryManifestPaths is computed correctly.
}
