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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;

public class TestExecutionContextUtils {

  public static ExecutionContext.Builder executionContextBuilder() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(filesystem);

    return ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setBuckEventBus(BuckEventBusForTests.newInstance())
        .setPlatform(Platform.detect())
        .setEnvironment(EnvVariablesProvider.getSystemEnv())
        .setClassLoaderCache(new ClassLoaderCache())
        .setProcessExecutor(new FakeProcessExecutor())
        .setCellPathResolver(cellPathResolver)
        .setProjectFilesystemFactory(new DefaultProjectFilesystemFactory())
        .setBuildCellRootPath(filesystem.getRootPath().getPath())
        .setClock(FakeClock.doNotCare());
  }
}
