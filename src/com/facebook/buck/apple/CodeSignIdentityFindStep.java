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

import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Utility step helping to find code sign identity during {@link AppleBundle} that was selected in
 * {@link AppleCodeSignPreparation} build rule
 */
public class CodeSignIdentityFindStep implements Step {

  private final Path codeSignIdentityFingerprint;
  private final ProjectFilesystem filesystem;
  private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;
  private final BuildStepResultHolder<CodeSignIdentity> result;

  public CodeSignIdentityFindStep(
      Path codeSignIdentityFingerprint,
      ProjectFilesystem filesystem,
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      BuildStepResultHolder<CodeSignIdentity> result) {
    this.codeSignIdentityFingerprint = codeSignIdentityFingerprint;
    this.filesystem = filesystem;
    this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    this.result = result;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    String fingerprint =
        filesystem
            .readFileIfItExists(codeSignIdentityFingerprint)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "File with code sign identity fingerprint is missing"))
            .trim();
    if (fingerprint.isEmpty()) {
      result.setValue(CodeSignIdentity.AD_HOC);
    } else {
      Optional<CodeSignIdentity> maybeCodeSignIdentity =
          codeSignIdentitiesSupplier.get().stream()
              .filter(
                  p -> p.getFingerprint().map(HashCode::toString).equals(Optional.of(fingerprint)))
              .findFirst();
      result.setValue(
          maybeCodeSignIdentity.orElseThrow(
              () ->
                  new IllegalStateException(
                      String.format(
                          "Code sign identity with fingerprint from %s not found",
                          codeSignIdentityFingerprint))));
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "code-sign-identity-find";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return String.format("code-sign-identity-find %s", codeSignIdentityFingerprint);
  }
}
