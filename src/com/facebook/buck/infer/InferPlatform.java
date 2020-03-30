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

package com.facebook.buck.infer;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import java.util.Optional;

/**
 * Represents Infer binary (path + version) with general configuration flags.
 *
 * <p>Changing any of those may invalidate the result of the analysis, so they are bundled together.
 */
@BuckStyleValue
public abstract class InferPlatform implements AddsToRuleKey {
  // TODO(arr): use content hash as a rule key instead
  @AddToRuleKey
  abstract Tool getInferBin();

  @AddToRuleKey
  abstract Optional<String> getInferVersion();

  // TODO(arr): use content hash as a rule key instead
  @AddToRuleKey
  abstract Optional<SourcePath> getInferConfig();

  @AddToRuleKey
  abstract Optional<SourcePath> getNullsafeThirdPartySignatures();

  /** Whether or not infer rules can be executed remotely. Fails serialization if false. */
  @AddToRuleKey
  @CustomFieldBehavior(RemoteExecutionEnabled.class)
  abstract boolean executeRemotely();

  public static InferPlatform of(
      Tool inferBin,
      Optional<String> inferVersion,
      Optional<? extends SourcePath> inferConfig,
      Optional<? extends SourcePath> nullsafeThirdPartySignatures,
      boolean executeRemotely) {
    return ImmutableInferPlatform.of(
        inferBin, inferVersion, inferConfig, nullsafeThirdPartySignatures, executeRemotely);
  }
}
