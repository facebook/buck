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

package com.facebook.buck.step;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class TestExecutionContext {

  private TestExecutionContext() {
    // Utility class.
  }

  // For test code, use a global class loader cache to avoid having to call ExecutionContext.close()
  // in each test case.
  private static ClassLoaderCache testClassLoaderCache = new ClassLoaderCache();

  public static ExecutionContext.Builder newBuilder() {
    Map<ExecutorPool, ListeningExecutorService> executors = new HashMap<>();
    executors.put(ExecutorPool.CPU, MoreExecutors.listeningDecorator(
        Executors.newCachedThreadPool()));
    return ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setBuckEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .setEnvironment(ImmutableMap.copyOf(System.getenv()))
        .setJavaPackageFinder(new FakeJavaPackageFinder())
        .setObjectMapper(ObjectMappers.newDefaultInstance())
        .setClassLoaderCache(testClassLoaderCache)
        .setExecutors(executors);
  }

  public static ExecutionContext newInstance() {
    return newBuilder().build();
  }
}
