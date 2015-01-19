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

import com.facebook.buck.io.FakeDirectoryTraverser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link AppleResource}.
 */
public class AppleResourceTest {

  /**
   * Tests an apple_resource rule with no file or directory resources.
   */
  @Test
  public void testAppleResourceRuleWithNoResources() throws IOException {
    AppleResourceDescription.Arg args = new AppleResourceDescription.Arg();
    args.dirs = ImmutableSortedSet.of();
    args.files = ImmutableSortedSet.of();
    args.variants = Optional.absent();

    AppleResource appleResource = new AppleResource(
        new FakeBuildRuleParamsBuilder(
            BuildTarget.builder("//path/to/app", "MyApp").build()).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        new FakeDirectoryTraverser(),
        args);

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    Path outputDir = projectFilesystem.resolve(Paths.get("buck-out/bin/path/to/app/MyApp.app"));

    MoreAsserts.assertSteps(
        "There are no copy steps",
        ImmutableList.of(
            String.format("rm -r -f %s && mkdir -p %s", outputDir, outputDir)),
        steps,
        executionContext);
  }

  /**
   * Tests an apple_resource rule with one file resource.
   */
  @Test
  public void testAppleResourceRuleWithOneResource() throws IOException {
    AppleResourceDescription.Arg args = new AppleResourceDescription.Arg();
    args.dirs = ImmutableSortedSet.of();
    args.files = ImmutableSortedSet.<SourcePath>of(new TestSourcePath("image.png"));
    args.variants = Optional.absent();

    AppleResource appleResource = new AppleResource(
        new FakeBuildRuleParamsBuilder(
            BuildTarget.builder("//path/to/app", "MyApp").build()).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        new FakeDirectoryTraverser(),
        args);

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    Path outputDir = projectFilesystem.resolve(Paths.get("buck-out/bin/path/to/app/MyApp.app"));

    MoreAsserts.assertSteps("Copy the resources to the expected location",
        ImmutableList.of(
            String.format("rm -r -f %s && mkdir -p %s", outputDir, outputDir),
            "cp image.png buck-out/bin/path/to/app/MyApp.app/image.png"),
        steps,
        executionContext);
  }

  /**
   * Tests an apple_resource rule with a directory resource.
   */
  @Test
  public void testAppleResourceRuleWithDirectoryResource() throws IOException {
    AppleResourceDescription.Arg args = new AppleResourceDescription.Arg();
    args.dirs = ImmutableSortedSet.of(Paths.get("MyLibrary.bundle"));
    args.files = ImmutableSortedSet.of();
    args.variants = Optional.absent();

    AppleResource appleResource = new AppleResource(
        new FakeBuildRuleParamsBuilder(
            BuildTarget.builder("//path/to/app", "MyApp").build()).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        new FakeDirectoryTraverser(),
        args);

    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    List<Step> steps = appleResource.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    Path outputDir = projectFilesystem.resolve(Paths.get("buck-out/bin/path/to/app/MyApp.app"));

    MoreAsserts.assertSteps("Copy the resources to the expected location",
        ImmutableList.of(
            String.format("rm -r -f %s && mkdir -p %s", outputDir, outputDir),
            "cp -R MyLibrary.bundle buck-out/bin/path/to/app/MyApp.app"),
        steps,
        executionContext);
  }

  /**
   * Tests the inputs of an apple_resource rule with a directory, files, and variants.
   */
  @Test
  public void testInputsForRuleWithDirectoryAndFiles() throws IOException {
    AppleResourceDescription.Arg args = new AppleResourceDescription.Arg();
    args.dirs = ImmutableSortedSet.of(Paths.get("MyLibrary.bundle"));
    args.files = ImmutableSortedSet.<SourcePath>of(new TestSourcePath("Resources/MyImage.jpg"));

    Map<String, SourcePath> variant =
        ImmutableMap.<String, SourcePath>of("en", new TestSourcePath("Resources/Real.jpg"));
    Map<String, Map<String, SourcePath>> variants =
        ImmutableMap.of("Virtual.jpg", variant);
    args.variants = Optional.of(variants);

    AppleResource appleResource = new AppleResource(
        new FakeBuildRuleParamsBuilder(
            BuildTarget.builder("//path/to/app", "MyApp").build()).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        // Pretend that MyLibrary.bundle contains two files: an image and a sound file.
        new FakeDirectoryTraverser(
            ImmutableMap.<String, Collection<FakeDirectoryTraverser.Entry>>of(
                "MyLibrary.bundle",
                ImmutableList.of(
                    new FakeDirectoryTraverser.Entry(null, "BundleImage.jpg"),
                    new FakeDirectoryTraverser.Entry(null, "BundleSound.wav")
                ))),
        args);

    MoreAsserts.assertIterablesEquals(
        "Directory should be traversed and file should be included.",
        ImmutableList.of(
            Paths.get("MyLibrary.bundle/BundleImage.jpg"),
            Paths.get("MyLibrary.bundle/BundleSound.wav"),
            Paths.get("Resources/MyImage.jpg"),
            Paths.get("Resources/Real.jpg")),
          appleResource.getInputsToCompareToOutput());
  }

  /**
   * Ensure the getters provide access to the args in sorted order.
   */
  @Test
  public void testGettersForDirsAndFiles() throws IOException {
    AppleResourceDescription.Arg args = new AppleResourceDescription.Arg();
    args.dirs = ImmutableSortedSet.of(Paths.get("MyLibrary.bundle"), Paths.get("Another.bundle"));
    args.files = ImmutableSortedSet.<SourcePath>of(
        new TestSourcePath("Resources/MySound.wav"),
        new TestSourcePath("Resources/MyImage.jpg"));
    args.variants = Optional.absent();

    AppleResource appleResource = new AppleResource(
        new FakeBuildRuleParamsBuilder(
            BuildTarget.builder("//path/to/app", "MyApp").build()).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        new FakeDirectoryTraverser(),
        args);

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(
            Paths.get("Another.bundle"),
            Paths.get("MyLibrary.bundle")),
        appleResource.getDirs());

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(
            new TestSourcePath("Resources/MyImage.jpg"),
            new TestSourcePath("Resources/MySound.wav")),
        appleResource.getFiles());
  }
}
