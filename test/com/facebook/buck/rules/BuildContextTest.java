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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.BuckEventBusFactory.CapturingConsoleEventListener;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.base.Supplier;

import org.junit.Test;

public class BuildContextTest {

  @Test(expected = HumanReadableException.class)
  public void testGetAndroidPlatformTargetSupplierWithNoneSpecified() {
    BuildContext.Builder builder = BuildContext.builder();

    // Set to non-null values.
    builder.setActionGraph(createMock(ActionGraph.class));
    builder.setArtifactCache(createMock(ArtifactCache.class));
    builder.setJavaPackageFinder(createMock(JavaPackageFinder.class));
    builder.setEventBus(BuckEventBusFactory.newInstance());
    builder.setClock(createMock(Clock.class));
    builder.setBuildId(createMock(BuildId.class));
    builder.setObjectMapper(ObjectMappers.newDefaultInstance());

    BuildContext context = builder.build();
    Supplier<AndroidPlatformTarget> supplier = context.getAndroidPlatformTargetSupplier();

    // If no AndroidPlatformTarget is passed to the builder, it should return a Supplier whose get()
    // method throws an exception.
    supplier.get();
  }

  @Test
  public void testLogError() {
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    CapturingConsoleEventListener listener = new CapturingConsoleEventListener();
    eventBus.register(listener);
    BuildContext buildContext = BuildContext.builder()
        .setActionGraph(createMock(ActionGraph.class))
        .setArtifactCache(createMock(ArtifactCache.class))
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setClock(createMock(Clock.class))
        .setBuildId(createMock(BuildId.class))
        .setEventBus(eventBus)
        .setObjectMapper(ObjectMappers.newDefaultInstance())
        .build();

    buildContext.logError(new RuntimeException(), "Error detail: %s", "BUILD_ID");
    assertThat(listener.getLogMessages(), contains(containsString("Error detail: BUILD_ID")));
  }
}
