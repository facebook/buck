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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import org.immutables.value.Value;

public interface DependencyFileRuleKeyFactory {

  /**
   * @return a {@link RuleKey} for the given {@link BuildRule} using the given list of explicit
   *     {@code inputs} and an {@link ImmutableSet} of the members of possibleDepFileSourcePaths
   *     that were actually used in constructing the key.
   */
  RuleKeyAndInputs build(
      SupportsDependencyFileRuleKey rule, ImmutableList<DependencyFileEntry> inputs)
      throws IOException;

  /**
   * @return the {@link RuleKey} used to index the manifest database and the list of inputs that
   *     should appear in the manifest (i.e., those that appeared in the dependency file, because
   *     all other inputs would be accounted for in the manifest key itself).
   */
  RuleKeyAndInputs buildManifestKey(SupportsDependencyFileRuleKey rule) throws IOException;

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractRuleKeyAndInputs {
    RuleKey getRuleKey();

    ImmutableSet<SourcePath> getInputs();
  }
}
