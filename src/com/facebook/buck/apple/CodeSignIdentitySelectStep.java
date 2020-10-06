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
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.function.Supplier;

/** Step that selects code sign identity appropriate for provided provisioning profile. */
public class CodeSignIdentitySelectStep extends AbstractExecutionStep {

  private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;
  private final Supplier<ProvisioningProfileMetadata> selectedProfileSupplier;
  private final BuildStepResultHolder<CodeSignIdentity> result;

  public CodeSignIdentitySelectStep(
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      Supplier<ProvisioningProfileMetadata> selectedProfileSupplier,
      BuildStepResultHolder<CodeSignIdentity> result) {
    super("code-sign-identity-select");
    this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    this.selectedProfileSupplier = selectedProfileSupplier;
    this.result = result;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

    ProvisioningProfileMetadata selectedProfile = selectedProfileSupplier.get();
    ImmutableSet<HashCode> fingerprints = selectedProfile.getDeveloperCertificateFingerprints();
    if (fingerprints.isEmpty()) {
      // No constraints, pick an arbitrary identity.
      // If no identities are available, use an ad-hoc identity.
      result.setValue(
          Iterables.getFirst(codeSignIdentitiesSupplier.get(), CodeSignIdentity.AD_HOC));
      return StepExecutionResults.SUCCESS;
    }
    for (CodeSignIdentity identity : codeSignIdentitiesSupplier.get()) {
      if (identity.getFingerprint().isPresent()
          && fingerprints.contains(identity.getFingerprint().get())) {
        result.setValue(identity);
        return StepExecutionResults.SUCCESS;
      }
    }
    throw new HumanReadableException(
        "No code sign identity available for provisioning profile: %s\n"
            + "Profile requires an identity with one of the following SHA1 fingerprints "
            + "available in your keychain: \n  %s",
        selectedProfile.getProfilePath(), Joiner.on("\n  ").join(fingerprints));
  }
}
