/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import java.util.Optional;
import javax.annotation.Nullable;

public interface RuleKeyFactory<RULE_KEY> {

  /**
   * Creates a new {@code RULE_KEY} for the given {@link BuildRule}. In most cases {@code RULE_KEY}
   * is going to be {@link RuleKey}, but it can be anything really.
   *
   * @param buildRule The build rule to create the key for.
   * @return A rule key.
   */
  RULE_KEY build(BuildRule buildRule);

  /**
   * Returns a {@code RULE_KEY} from an internal cache, if possible. If a non-null value is
   * returned, it is guaranteed to be he same as if {@link #build} were called instead.
   */
  @Nullable
  @SuppressWarnings("unused")
  default RULE_KEY getFromCache(BuildRule buildRule) {
    return null;
  }

  default Optional<Long> getInputSizeLimit() {
    return Optional.empty();
  }
}
