/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;

/** A rule key factory that provides diagnostic facilities. */
public interface RuleKeyFactoryWithDiagnostics<RULE_KEY> extends RuleKeyFactory<RULE_KEY> {

  /**
   * Builds a diagnostic result for the given rule or appendable.
   *
   * <p>This method is intended to be used exclusively for diagnostic purposes and not for computing
   * rule keys used for build process in any other way.
   *
   * <p>The provided custom hasher is used for all the elements hashed under this rule or
   * appendable.
   *
   * <p>Note however that if the factory chooses to hash nested build rules and appendables
   * separately, and only include their final hash for the computation of this hash, this choice
   * applies both to real rule keys and the diagnostic keys. The client may need to perform a
   * separate call for each build rule or appendable of interest. Moreover, the hash for the nested
   * build rules and appendables is obtained by using the default hasher and not the provided custom
   * hasher. This is the natural choice as it allows the custom hasher to see precisely those
   * elements that the default hasher sees. It is also more efficient because factories usually
   * cache rule keys computed with the default hasher, whereas using the custom hasher prevents that
   * and would require hashing all of its transitive dependencies.
   */
  <DIAG_KEY> RuleKeyDiagnostics.Result<RULE_KEY, DIAG_KEY> buildForDiagnostics(
      BuildRule buildRule, RuleKeyHasher<DIAG_KEY> hasher);

  <DIAG_KEY> RuleKeyDiagnostics.Result<RULE_KEY, DIAG_KEY> buildForDiagnostics(
      AddsToRuleKey appendable, RuleKeyHasher<DIAG_KEY> hasher);
}
