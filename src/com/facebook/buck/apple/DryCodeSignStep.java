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

import com.dd.plist.NSDictionary;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Step that writes down the settings instead of actually performing code signing */
class DryCodeSignStep implements Step {

  private final Path pathToSign;
  private final boolean shouldUseEntitlements;
  private final Supplier<CodeSignIdentity> codeSignIdentitySupplier;
  private final Pair<Path, ImmutableList<Path>> dryRunResultsWithExtraPaths;
  private final ProjectFilesystem filesystem;

  public DryCodeSignStep(
      ProjectFilesystem filesystem,
      Path pathToSign,
      boolean shouldUseEntitlements,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier,
      Pair<Path, ImmutableList<Path>> dryRunResultsWithExtraPaths) {
    this.filesystem = filesystem;
    this.pathToSign = pathToSign;
    this.shouldUseEntitlements = shouldUseEntitlements;
    this.codeSignIdentitySupplier = codeSignIdentitySupplier;
    this.dryRunResultsWithExtraPaths = dryRunResultsWithExtraPaths;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    Path dryRunResultsPath = dryRunResultsWithExtraPaths.getFirst();
    NSDictionary dryRunResult = new NSDictionary();
    dryRunResult.put(
        "relative-path-to-sign", dryRunResultsPath.getParent().relativize(pathToSign).toString());
    dryRunResult.put("use-entitlements", shouldUseEntitlements);
    dryRunResult.put("identity", CodeSignStep.getIdentityArg(codeSignIdentitySupplier.get()));

    ImmutableList<Path> extraPathsToSign = dryRunResultsWithExtraPaths.getSecond();
    if (!extraPathsToSign.isEmpty()) {
      ImmutableList.Builder<String> extraPathsBuilder = ImmutableList.builder();
      for (Path extraPath : extraPathsToSign) {
        extraPathsBuilder.add(dryRunResultsPath.getParent().relativize(extraPath).toString());
      }
      ImmutableList<String> extraPaths = extraPathsBuilder.build();
      dryRunResult.put("extra-paths-to-sign", extraPaths);
    }

    filesystem.writeContentsToPath(dryRunResult.toXMLPropertyList(), dryRunResultsPath);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "dry-code-sign";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return String.format("dry-code-sign %s", pathToSign);
  }
}
