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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.ImmutableProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;

public class AppleConfig {

  private final BuckConfig delegate;

  public AppleConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * If specified, the value of {@code [apple] xcode_developer_dir} wrapped in a {@link Supplier}.
   * Otherwise, this returns a {@link Supplier} that lazily runs {@code xcode-select --print-path}
   * and caches the result.
   */
  public Supplier<Path> getAppleDeveloperDirectorySupplier(ProcessExecutor processExecutor) {
    Optional<String> xcodeDeveloperDirectory = delegate.getValue("apple", "xcode_developer_dir");
    if (xcodeDeveloperDirectory.isPresent()) {
      Path developerDirectory = delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(
          Paths.get(xcodeDeveloperDirectory.get()));
      return Suppliers.ofInstance(developerDirectory);
    } else {
      return createAppleDeveloperDirectorySupplier(processExecutor);
    }
  }

  public ImmutableMap<AppleSdk, AppleSdkPaths> getAppleSdkPaths(ProcessExecutor processExecutor) {
    Path appleDeveloperDirectory = getAppleDeveloperDirectorySupplier(processExecutor).get();
    try {
      ImmutableMap<String, Path> toolchainPaths =
          AppleToolchainDiscovery.discoverAppleToolchainPaths(appleDeveloperDirectory);
      return AppleSdkDiscovery.discoverAppleSdkPaths(
          appleDeveloperDirectory,
          appleDeveloperDirectory.getParent().resolve("version.plist"),
          toolchainPaths);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return a memoizing {@link Supplier} that caches the output of
   *     {@code xcode-select --print-path}.
   */
  private static Supplier<Path> createAppleDeveloperDirectorySupplier(
      final ProcessExecutor processExecutor) {
    return Suppliers.memoize(new Supplier<Path>() {
      @Override
      public Path get() {
        ProcessExecutorParams processExecutorParams =
            ImmutableProcessExecutorParams.builder()
                .setCommand(ImmutableList.of("xcode-select", "--print-path"))
                .build();
        // Must specify that stdout is expected or else output may be wrapped in Ansi escape chars.
        Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
        ProcessExecutor.Result result;
        try {
          result = processExecutor.launchAndExecute(
              processExecutorParams,
              options,
              /* stdin */ Optional.<String>absent(),
              /* timeOutMs */ Optional.<Long>absent());
        } catch (InterruptedException | IOException e) {
          throw new RuntimeException(e);
        }

        if (result.getExitCode() != 0) {
          throw new RuntimeException("xcode-select --print-path failed: " + result.getStderr());
        }

        return Paths.get(result.getStdout().get().trim());
      }
    });
  }

  public Optional<String> getTargetSdkVersion(ApplePlatform platform) {
    return delegate.getValue("apple", platform.toString() + "_target_sdk_version");
  }
}
