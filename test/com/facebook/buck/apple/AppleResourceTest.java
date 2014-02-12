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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit tests for {@link AppleResource}.
 */
public class AppleResourceTest {

  /**
   * Tests an ios_resource rule with no resources.
   */
  @Test
  public void testIosResourceRuleWithNoResources() throws IOException {
    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//path/to/app", "MyApp"));

    AppleResourceDescriptionArg args = new AppleResourceDescriptionArg();
    args.resources = ImmutableSortedSet.of();

    AppleResource appleResource = new AppleResource(
        buildRuleParams,
        args,
        Optional.<Path>absent());

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    assertEquals(0, steps.size());
  }

  /**
   * Tests an ios_resource rule with one resources.
   */
  @Test
  public void testIosResourceRuleWithOneResource() throws IOException {
    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//path/to/app", "MyApp"));

    AppleResourceDescriptionArg args = new AppleResourceDescriptionArg();
    args.resources = ImmutableSortedSet.of(Paths.get("image.png"));

    AppleResource appleResource = new AppleResource(
        buildRuleParams,
        args,
        Optional.<Path>absent());

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    MoreAsserts.assertSteps("Copy the resources to the expected location",
        ImmutableList.of(
            "cp image.png buck-out/bin/path/to/app/MyApp.app"),
        steps,
        executionContext);
  }

  /**
   * Tests an osx_resource rule with no resources.
   */
  @Test
  public void testOsxResourceRuleWithNoResources() throws IOException {
    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//path/to/app", "MyApp"));

    AppleResourceDescriptionArg args = new AppleResourceDescriptionArg();
    args.resources = ImmutableSortedSet.of();

    AppleResource appleResource = new AppleResource(
        buildRuleParams,
        args,
        Optional.of(Paths.get("Resources")));

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    assertEquals(0, steps.size());
  }

  /**
   * Tests an osx_resource rule with one resources.
   */
  @Test
  public void testOsxResourceRuleWithOneResource() throws IOException {
    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//path/to/app", "MyApp"));

    AppleResourceDescriptionArg args = new AppleResourceDescriptionArg();
    args.resources = ImmutableSortedSet.of(Paths.get("image.png"));

    AppleResource appleResource = new AppleResource(
        buildRuleParams,
        args,
        Optional.of(Paths.get("Resources")));

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    MoreAsserts.assertSteps("Copy the resources to the expected location",
        ImmutableList.of(
            "cp image.png buck-out/bin/path/to/app/MyApp.app/Resources"),
        steps,
        executionContext);
  }
}
