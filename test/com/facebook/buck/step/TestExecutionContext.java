/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;

public class TestExecutionContext {

  private TestExecutionContext() {
    // Utility class.
  }

  // For test code, use a global class loader cache to avoid having to call ExecutionContext.close()
  // in each test case.
  private static final ClassLoaderCache testClassLoaderCache = new ClassLoaderCache();

  public static StepExecutionContext.Builder newBuilder() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    AbsPath rootPath = filesystem.getRootPath();
    return StepExecutionContext.builder()
        .setConsole(new TestConsole())
        .setBuckEventBus(BuckEventBusForTests.newInstance())
        .setPlatform(Platform.detect())
        .setEnvironment(EnvVariablesProvider.getSystemEnv())
        .setClassLoaderCache(testClassLoaderCache)
        .setProcessExecutor(new FakeProcessExecutor())
        .setProjectFilesystemFactory(new DefaultProjectFilesystemFactory())
        .setBuildCellRootPath(rootPath.getPath())
        .setRuleCellRoot(rootPath)
        .setActionId("test_action_id")
        .setClock(FakeClock.doNotCare());
  }

  public static StepExecutionContext newInstance() {
    return newBuilder().build();
  }

  public static StepExecutionContext newInstance(AbsPath root) {
    return newBuilder().setRuleCellRoot(root).setBuildCellRootPath(root.getPath()).build();
  }

  public static StepExecutionContext newInstanceWithRealProcessExecutor() {
    TestConsole console = new TestConsole();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(console);
    return newBuilder().setConsole(console).setProcessExecutor(processExecutor).build();
  }
}
