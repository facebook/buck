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

package com.facebook.buck.go;

import static org.junit.Assume.assumeNoException;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;

abstract class GoAssumptions {
  public static void assumeGoCompilerAvailable() throws InterruptedException, IOException {
    Throwable exception = null;
    try {
      ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());

      FakeBuckConfig.Builder baseConfig = FakeBuckConfig.builder();
      String goRoot = System.getenv("GOROOT");
      if (goRoot != null) {
        baseConfig.setSections("[go]", "  root = " + goRoot);
        // This should really act like some kind of readonly bind-mount onto the real filesystem.
        // But this is currently enough to check whether Go seems to be installed, so we'll live...
        FakeProjectFilesystem fs = new FakeProjectFilesystem();
        fs.mkdirs(fs.getPath(goRoot));
        baseConfig.setFilesystem(fs);
      }
      new GoToolchainFactory()
          .createToolchain(
              new ToolchainProviderBuilder().build(),
              ToolchainCreationContext.of(
                  ImmutableMap.of(),
                  baseConfig.build(),
                  new FakeProjectFilesystem(),
                  executor,
                  new ExecutableFinder()))
          .get()
          .getCompiler();
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }
}
