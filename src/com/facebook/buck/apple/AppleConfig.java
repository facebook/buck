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
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

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
  public Supplier<Path> getAppleDeveloperDirectorySupplier(Console console) {
    Optional<String> xcodeDeveloperDirectory = delegate.getValue("apple", "xcode_developer_dir");
    if (xcodeDeveloperDirectory.isPresent()) {
      Path developerDirectory = Paths.get(xcodeDeveloperDirectory.get());
      return Suppliers.ofInstance(developerDirectory);
    } else {
      return createAppleDeveloperDirectorySupplier(console);
    }
  }

  /**
   * @return a memoizing {@link Supplier} that caches the output of
   *     {@code xcode-select --print-path}.
   */
  private static Supplier<Path> createAppleDeveloperDirectorySupplier(final Console console) {
    return Suppliers.memoize(new Supplier<Path>() {
      @Override
      public Path get() {
        ProcessBuilder processBuilder = new ProcessBuilder("xcode-select", "--print-path");
        // Must specify that stdout is expected or else output may be wrapped in Ansi escape chars.
        Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
        ProcessExecutor processExecutor = new ProcessExecutor(console);
        ProcessExecutor.Result result;
        try {
          result = processExecutor.execute(
              processBuilder.start(),
              options,
              /* stdin */ Optional.<String>absent());
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
}
