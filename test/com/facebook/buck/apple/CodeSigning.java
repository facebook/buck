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

package com.facebook.buck.apple;

import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Utility class for code signing. */
public class CodeSigning {
  // Utility class, do not instantiate.
  private CodeSigning() {}

  /**
   * Checks whether a binary or bundle already has a valid code signature.
   *
   * @param path Resolved path to the binary or bundle.
   * @return Whether the binary or bundle has a valid code signature.
   */
  public static boolean hasValidSignature(ProcessExecutor processExecutor, Path path)
      throws InterruptedException, IOException {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("codesign", "--verify", "-v", path.toString()))
            .build();

    // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options =
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT);

    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());

    return result.getExitCode() == 0
        && result.getStderr().isPresent()
        && result.getStderr().get().contains(": satisfies its Designated Requirement");
  }

  /**
   * Checks whether a binary or bundle contains specific entitlement.
   *
   * @param path Resolved path to the binary or bundle.
   * @param entitlementKey Entitlement key to check.
   * @return Whether the binary or bundle contains the entitlement.
   */
  public static boolean hasEntitlement(
      ProcessExecutor processExecutor, Path path, String entitlementKey)
      throws InterruptedException, IOException {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("codesign", "-d", "--entitlements", ":-", path.toString()))
            .build();

    // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options =
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT);

    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());

    return result.getExitCode() == 0
        && result.getStdout().isPresent()
        && result.getStdout().get().contains(entitlementKey);
  }
}
