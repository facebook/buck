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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.BuckEventBusFactory.CapturingLogEventListener;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class BuildContextTest {

  @Test
  public void testGetAndroidBootclasspathSupplierWithAndroidPlatformTarget() {
    BuildContext.Builder builder = BuildContext.builder();

    // Set to non-null values.
    builder.setDependencyGraph(createMock(DependencyGraph.class));
    builder.setStepRunner(createMock(StepRunner.class));
    builder.setProjectFilesystem(createMock(ProjectFilesystem.class));
    builder.setArtifactCache(createMock(ArtifactCache.class));
    builder.setJavaPackageFinder(createMock(JavaPackageFinder.class));
    builder.setEventBus(BuckEventBusFactory.newInstance());

    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    List<Path> entries = ImmutableList.of(
        Paths.get("add-ons/addon-google_apis-google-15/libs/effects.jar"),
        Paths.get("add-ons/addon-google_apis-google-15/libs/maps.jar"),
        Paths.get("add-ons/addon-google_apis-google-15/libs/usb.jar"));
    expect(androidPlatformTarget.getBootclasspathEntries()).andReturn(entries);

    replay(androidPlatformTarget);

    builder.setAndroidBootclasspathForAndroidPlatformTarget(Optional.of(androidPlatformTarget));

    BuildContext context = builder.build();
    Supplier<String> androidBootclasspathSupplier = context.getAndroidBootclasspathSupplier();

    String androidBootclasspath = MorePaths.pathWithUnixSeparators(
        androidBootclasspathSupplier.get());
    assertEquals(
        "add-ons/addon-google_apis-google-15/libs/effects.jar:" +
        "add-ons/addon-google_apis-google-15/libs/maps.jar:" +
        "add-ons/addon-google_apis-google-15/libs/usb.jar",
        androidBootclasspath);

    // Call get() again to ensure that the underlying getBootclasspathEntries() is not called again
    // to verify that memoization is working as expected.
    androidBootclasspathSupplier.get();

    verify(androidPlatformTarget);
  }

  @Test(expected = HumanReadableException.class)
  public void testGetAndroidBootclasspathSupplierWithoutAndroidPlatformTarget() {
    BuildContext.Builder builder = BuildContext.builder();

    // Set to non-null values.
    builder.setDependencyGraph(createMock(DependencyGraph.class));
    builder.setStepRunner(createMock(StepRunner.class));
    builder.setProjectFilesystem(createMock(ProjectFilesystem.class));
    builder.setArtifactCache(createMock(ArtifactCache.class));
    builder.setJavaPackageFinder(createMock(JavaPackageFinder.class));
    builder.setEventBus(BuckEventBusFactory.newInstance());

    BuildContext context = builder.build();
    Supplier<String> androidBootclasspathSupplier = context.getAndroidBootclasspathSupplier();

    // If no AndroidPlatformTarget is passed to the builder, it should return a Supplier whose get()
    // method throws an exception.
    androidBootclasspathSupplier.get();
  }

  @Test(expected = HumanReadableException.class)
  public void testGetAndroidBootclasspathSupplierWithAbsentAndroidPlatformTarget() {
    BuildContext.Builder builder = BuildContext.builder();

    // Set to non-null values.
    builder.setDependencyGraph(createMock(DependencyGraph.class));
    builder.setStepRunner(createMock(StepRunner.class));
    builder.setProjectFilesystem(createMock(ProjectFilesystem.class));
    builder.setArtifactCache(createMock(ArtifactCache.class));
    builder.setJavaPackageFinder(createMock(JavaPackageFinder.class));
    builder.setEventBus(BuckEventBusFactory.newInstance());

    // Set to absent value.
    builder.setAndroidBootclasspathForAndroidPlatformTarget(
        Optional.<AndroidPlatformTarget>absent());

    BuildContext context = builder.build();
    Supplier<String> androidBootclasspathSupplier = context.getAndroidBootclasspathSupplier();

    // If no AndroidPlatformTarget is passed to the builder, it should return a Supplier whose get()
    // method throws an exception.
    androidBootclasspathSupplier.get();
  }

  @Test
  public void testLogBuildInfo() {
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    CapturingLogEventListener listener = new CapturingLogEventListener();
    eventBus.register(listener);
    BuildContext buildContext = BuildContext.builder()
        .setDependencyGraph(createMock(DependencyGraph.class))
        .setStepRunner(createMock(StepRunner.class))
        .setProjectFilesystem(createMock(ProjectFilesystem.class))
        .setArtifactCache(createMock(ArtifactCache.class))
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(eventBus)
        .build();

    buildContext.logBuildInfo("My name is %s and I can count to %d.", "Michael", 10);
    assertEquals(ImmutableList.of("My name is Michael and I can count to 10."),
        listener.getLogMessages());
  }

  @Test
  public void testLogError() {
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    CapturingLogEventListener listener = new CapturingLogEventListener();
    eventBus.register(listener);
    BuildContext buildContext = BuildContext.builder()
        .setDependencyGraph(createMock(DependencyGraph.class))
        .setStepRunner(createMock(StepRunner.class))
        .setProjectFilesystem(createMock(ProjectFilesystem.class))
        .setArtifactCache(createMock(ArtifactCache.class))
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(eventBus)
        .build();

    buildContext.logError(new RuntimeException(), "Error detail: %s", "BUILD_ID");
    assertEquals(ImmutableList.of("Error detail: BUILD_ID"), listener.getLogMessages());
  }
}
