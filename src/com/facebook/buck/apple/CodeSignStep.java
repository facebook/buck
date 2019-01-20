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

package com.facebook.buck.apple;

import com.dd.plist.NSDictionary;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

class CodeSignStep implements Step {

  private static final Logger LOG = Logger.get(CodeSignStep.class);

  private final SourcePathResolver resolver;
  private final Path pathToSign;
  private final Optional<Path> pathToSigningEntitlements;
  private final Supplier<CodeSignIdentity> codeSignIdentitySupplier;
  private final Tool codesign;
  private final Optional<Tool> codesignAllocatePath;
  private final Optional<Path> dryRunResultsPath;
  private final ProjectFilesystem filesystem;
  private final ImmutableList<String> codesignFlags;
  private final Duration codesignTimeout;

  public CodeSignStep(
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      Path pathToSign,
      Optional<Path> pathToSigningEntitlements,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier,
      Tool codesign,
      Optional<Tool> codesignAllocatePath,
      Optional<Path> dryRunResultsPath,
      ImmutableList<String> codesignFlags,
      Duration codesignTimeout) {
    this.filesystem = filesystem;
    this.resolver = resolver;
    this.pathToSign = pathToSign;
    this.pathToSigningEntitlements = pathToSigningEntitlements;
    this.codeSignIdentitySupplier = codeSignIdentitySupplier;
    this.codesign = codesign;
    this.codesignAllocatePath = codesignAllocatePath;
    this.dryRunResultsPath = dryRunResultsPath;
    this.codesignFlags = codesignFlags;
    this.codesignTimeout = codesignTimeout;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (dryRunResultsPath.isPresent()) {
      NSDictionary dryRunResult = new NSDictionary();
      dryRunResult.put(
          "relative-path-to-sign",
          dryRunResultsPath.get().getParent().relativize(pathToSign).toString());
      dryRunResult.put("use-entitlements", pathToSigningEntitlements.isPresent());
      dryRunResult.put("identity", getIdentityArg(codeSignIdentitySupplier.get()));
      filesystem.writeContentsToPath(dryRunResult.toXMLPropertyList(), dryRunResultsPath.get());
      return StepExecutionResults.SUCCESS;
    }

    ProcessExecutorParams.Builder paramsBuilder = ProcessExecutorParams.builder();
    if (codesignAllocatePath.isPresent()) {
      ImmutableList<String> commandPrefix = codesignAllocatePath.get().getCommandPrefix(resolver);
      paramsBuilder.setEnvironment(
          ImmutableMap.of("CODESIGN_ALLOCATE", Joiner.on(" ").join(commandPrefix)));
    }
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(codesign.getCommandPrefix(resolver));
    commandBuilder.add("--force", "--sign", getIdentityArg(codeSignIdentitySupplier.get()));
    commandBuilder.addAll(codesignFlags);
    if (pathToSigningEntitlements.isPresent()) {
      commandBuilder.add("--entitlements", pathToSigningEntitlements.get().toString());
    }
    commandBuilder.add(pathToSign.toString());
    ProcessExecutorParams processExecutorParams =
        paramsBuilder
            .setCommand(commandBuilder.build())
            .setDirectory(filesystem.getRootPath())
            .build();
    // Must specify that stdout is expected or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor processExecutor = context.getProcessExecutor();
    if (LOG.isDebugEnabled()) {
      LOG.debug("codesign command: %s", Joiner.on(" ").join(processExecutorParams.getCommand()));
    }
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.of(codesignTimeout.toMillis()),
            /* timeOutHandler */ Optional.empty());

    if (result.isTimedOut()) {
      throw new RuntimeException(
          "codesign timed out.  This may be due to the keychain being locked.");
    }

    if (result.getExitCode() != 0) {
      return StepExecutionResult.of(result);
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "code-sign";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("code-sign %s", pathToSign);
  }

  /** Convert a {@link CodeSignIdentity} into a string argument for the codesign tool. */
  public static String getIdentityArg(CodeSignIdentity identity) {
    if (identity.getFingerprint().isPresent()) {
      return identity.getFingerprint().get().toString().toUpperCase();
    } else {
      if (identity.shouldUseSubjectCommonNameToSign()) {
        return identity.getSubjectCommonName();
      }
      return "-"; // ad-hoc
    }
  }
}
