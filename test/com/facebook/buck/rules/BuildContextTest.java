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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Supplier;
import org.junit.Test;

public class BuildContextTest {

  @Test(expected = HumanReadableException.class)
  public void testGetAndroidPlatformTargetSupplierWithNoneSpecified() {
    BuildContext.Builder builder = BuildContext.builder();

    // Set to non-null values.
    builder.setActionGraph(createMock(ActionGraph.class));
    builder.setSourcePathResolver(createMock(SourcePathResolver.class));
    builder.setJavaPackageFinder(createMock(JavaPackageFinder.class));
    builder.setEventBus(BuckEventBusFactory.newInstance());

    BuildContext context = builder.build();
    Supplier<AndroidPlatformTarget> supplier = context.getAndroidPlatformTargetSupplier();

    // If no AndroidPlatformTarget is passed to the builder, it should return a Supplier whose get()
    // method throws an exception.
    supplier.get();
  }
}
