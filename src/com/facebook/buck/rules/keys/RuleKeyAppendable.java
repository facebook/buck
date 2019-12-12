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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import java.nio.file.Path;

/**
 * Deprecated. Implement {@link AddsToRuleKey} instead, and use {@link AddToRuleKey} annotations on
 * fields to indicate which ones should be part of the rule key.
 *
 * <p>TODO(t6430785): Delete this class once no more usages exist
 */
@Deprecated
public interface RuleKeyAppendable extends AddsToRuleKey {
  /**
   * Deprecated. Implementations of {@link RuleKeyAppendable} use this to add things to rulekey
   * builders.
   */
  @Deprecated
  interface RuleKeyAppendableSink {

    /**
     * Add stringified paths as keys. The paths in represent include directives rather than actual
     * on-disk locations. One possibly non-obvious thing here is that the key will only be added to
     * the rulekey if the path is. If the rulekeybuilder ignores the path, the key won't be added.
     */
    @Deprecated
    void addValue(Path key, SourcePath path);
  }

  /** Deprecated. Add additional custom things to the rulekey builder. */
  @Deprecated
  void appendToRuleKey(RuleKeyAppendableSink sink);
}
