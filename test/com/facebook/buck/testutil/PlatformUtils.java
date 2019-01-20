/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * PlatformUtils exposes a consistent place to get potentially platform-specific configurations for
 * testing
 */
public abstract class PlatformUtils {
  private ExecutableFinder executableFinder = new ExecutableFinder();

  protected PlatformUtils() {}

  public String[] getVsToolchainDirs() {
    return new String[0];
  }

  public Optional<String> getObjcopy() {
    return Optional.empty();
  }

  protected String findExecutable(String bin) {
    try {
      Path executablePath =
          executableFinder.getExecutable(Paths.get(bin), EnvVariablesProvider.getSystemEnv());
      return executablePath.toAbsolutePath().toString();
    } catch (HumanReadableException e) {
      assumeNoException(e);
      throw new RuntimeException("Assumption in error should not allow access to this path");
    }
  }

  private void checkAssumptionValue(String name, Optional<String> configValue) {
    assumeTrue(
        String.format("%s should exist", name),
        !configValue.isPresent() || Files.isExecutable(Paths.get(configValue.get())));
  }

  private void checkAssumptionLists(String name, String[] configDir) {
    for (String dir : configDir) {
      assumeTrue(
          String.format("%s '%s' should exist", name, dir), Files.isDirectory(Paths.get(dir)));
    }
  }

  /**
   * Make sure that files we believe should exist do, if we don't, we shouldn't continue the test
   * that likely relies on it.
   */
  public void checkAssumptions() {
    checkAssumptionLists("vs toolchain", getVsToolchainDirs());
    checkAssumptionValue("objcopy", getObjcopy());
  }

  /** Returns the flavor of build rules for the given platform */
  public Optional<String> getFlavor() {
    return Optional.empty();
  }

  /**
   * Gets a base command builder for the given platform, where the utils adds in command necessary
   * to launch buck in the given environment
   */
  public abstract ImmutableList.Builder<String> getBuckCommandBuilder();

  /** Gets a base command builder for the given platform */
  public abstract ImmutableList.Builder<String> getCommandBuilder();

  /** Gets a PlatformUtils based on what Platform we're running tests in. */
  public static PlatformUtils getForPlatform() {
    switch (Platform.detect()) {
      case WINDOWS:
        return new WindowsUtils();
      case MACOS:
        return new MacOSUtils();
      case LINUX:
        return new LinuxUtils();
      default:
        throw new RuntimeException("Attempted to get platform utils for unknown platform.");
    }
  }
}
