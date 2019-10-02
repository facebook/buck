/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.infer;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
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
}
