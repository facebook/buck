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

package com.facebook.buck.testutil;

import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** An for abstraction of {@link PlatformUtils} for Unix platforms (Mac OS, Linux) */
public abstract class UnixUtils extends PlatformUtils {
  protected UnixUtils() {}

  /** Returns a command builder for a unix platform */
  @Override
  public ImmutableList.Builder<String> getCommandBuilder() {
    return ImmutableList.builder();
  }

  /** Returns a buck command builder for a unix platform, which runs buck through its .pex file */
  @Override
  public ImmutableList.Builder<String> getBuckCommandBuilder() {
    String buckExe = EnvVariablesProvider.getSystemEnv().get("TEST_BUCK");
    if (buckExe == null || buckExe.isEmpty()) {
      throw new AssertionError("TEST_BUCK env variable must be set");
    }

    Path buckExePath = Paths.get(buckExe);
    if (!Files.exists(buckExePath)) {
      throw new AssertionError("TEST_BUCK path " + buckExe + " does not exist");
    }

    if (!buckExePath.isAbsolute()) {
      throw new AssertionError("TEST_BUCK path " + buckExe + " must be absolute");
    }

    return getCommandBuilder().add(buckExe);
  }

  @Override
  public String getPython2Executable() {
    return getExecutableFinder()
        .getOptionalExecutable(Paths.get("python2"), EnvVariablesProvider.getSystemEnv())
        .map(Path::toString)
        .orElse("python");
  }
}
