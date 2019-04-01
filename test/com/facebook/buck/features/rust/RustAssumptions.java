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

package com.facebook.buck.features.rust;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

abstract class RustAssumptions {
  public static void assumeRustIsConfigured() {
    assumeFalse(Platform.detect() == Platform.WINDOWS);

    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    RustPlatform rustPlatform =
        RustPlatformFactory.of(FakeBuckConfig.builder().build(), new ExecutableFinder())
            .getPlatform("rust", CxxPlatformUtils.DEFAULT_PLATFORM);
    Throwable exception = null;
    try {
      rustPlatform
          .getRustCompiler()
          .resolve(resolver, EmptyTargetConfiguration.INSTANCE)
          .getCommandPrefix(pathResolver);
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }

  public static void assumeNightly(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    RustPlatform rustPlatform =
        RustPlatformFactory.of(FakeBuckConfig.builder().build(), new ExecutableFinder())
            .getPlatform("rust", CxxPlatformUtils.DEFAULT_PLATFORM);
    ImmutableList<String> rustc =
        rustPlatform
            .getRustCompiler()
            .resolve(resolver, EmptyTargetConfiguration.INSTANCE)
            .getCommandPrefix(pathResolver);

    Result res = workspace.runCommand(rustc.get(0), "-Zhelp");
    assumeTrue("Requires nightly Rust", res.getExitCode() == 0);
  }

  public static void assumeVersion(ProjectWorkspace workspace, String version)
      throws IOException, InterruptedException {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    RustPlatform rustPlatform =
        RustPlatformFactory.of(FakeBuckConfig.builder().build(), new ExecutableFinder())
            .getPlatform("rust", CxxPlatformUtils.DEFAULT_PLATFORM);
    ImmutableList<String> rustc =
        rustPlatform
            .getRustCompiler()
            .resolve(resolver, EmptyTargetConfiguration.INSTANCE)
            .getCommandPrefix(pathResolver);

    String[] versionParts = version.split("\\.");

    Result res = workspace.runCommand(rustc.get(0), "--version");
    String stdout = res.getStdout().get();

    // rustc 1.32.0 (9fda7c223 2019-01-16)
    String[] rustOut = stdout.split(" +", 3);
    assumeTrue(
        "rustc --version produced wrong output", rustOut.length == 3 && rustOut[0].equals("rustc"));

    String[] rustVersionParts = rustOut[1].split("\\.");

    for (int i = 0; i < versionParts.length && i < rustVersionParts.length; i++) {
      Integer rustcVer = Integer.parseInt(rustVersionParts[i]);
      Integer wantVer = Integer.parseInt(versionParts[i]);

      assumeTrue(
          String.format("rustc version %s doesn't meet %s", rustOut[1], version),
          rustcVer >= wantVer);

      // No need to check the less significant parts if this one is larger than required.
      if (rustcVer > wantVer) {
        break;
      }
    }
  }
}
