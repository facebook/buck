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
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.Optional;

/**
 * Used to tag a rule that supports dependency-file input-based rule keys.
 */
public interface SupportsDependencyFileRuleKey extends BuildRule {

  boolean useDependencyFileRuleKeys();

  /**
   * Returns a set of all possible source paths that may be returned from
   * {@link #getInputsAfterBuildingLocally()}. This information is used by the rule key builder
   * to infer that inputs *not* in this list should be included unconditionally in the rule key.
   *
   * If not present, we treat all the input files as covered by dep file, meaning that they will
   * be included in the rule key only if present in the dep file.
   *
   * TODO(jkeljo): This is only optional because I added it for Java and didn't have the time to go
   * back and figure out how to implement it for C++.
   */
  Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths();

  ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException;

}
