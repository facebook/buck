/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import javax.annotation.Nullable;

/**
 * {@link BuildRule} that can avoid rebuilding itself when the ABI of its deps has not changed and
 * all properties of the rule other than its deps have not changed.
 * <p>
 * Therefore, all deps of an {@link AbiRule} must also be {@link AbiRule}s themselves.
 */
public interface AbiRule {

  /**
   * Normally, a {@link RuleKey} is a function of the {@link RuleKey} of each of its deps as well as
   * that of its inputs. This returns a {@link RuleKey} that is a function of only its inputs, which
   * can be used to determine whether the definition or inputs of the rule changed independent of
   * changes to its [transitive] deps.
   *
   * @return {@null} if there is an error when computing the {@link RuleKey}
   */
  @Nullable
  public RuleKey getRuleKeyWithoutDeps();

  /**
   * This is the same as {@link #getRuleKeyWithoutDeps()}, but is the {@link RuleKey} for the output
   * that is currently written to disk, rather than the output that will be written once this
   * {@link AbiRule} is built. This is designed to be compared with the result of
   * {@link #getRuleKeyWithoutDeps()} as a heuristic for whether this rule needs to be rebuilt if
   * its deps [or the ABI of its deps] have not changed.
   *
   * @return {@code null} if this {@link BuildRule} has not been built locally before, in which case
   *     there is no {@link RuleKey} from a previous run.
   */
  @Nullable
  public RuleKey getRuleKeyWithoutDepsOnDisk();

  /**
   * If all of the deps of this rule are {@link AbiRule}s, then this will return an SHA-1 hash that
   * represents the union of their ABIs.
   *
   * @return {@code null} if not all deps are {@link AbiRule}s.
   */
  @Nullable
  public String getAbiKeyForDeps();

  /**
   * This is the same as {@link #getAbiKeyForDeps()}, but is the ABI key of this rule's deps that
   * was computed the last time this rule was built locally, so its value is read from disk. This is
   * designed to be compared with the result of {@link #getAbiKeyForDeps()} as a heuristic for
   * whether this rule needs to be rebuilt.
   *
   * @return {@code null} if the deps of this {@link BuildRule} have not been built locally before,
   *     in which case there is no data from a previous run.
   */
  @Nullable
  public String getAbiKeyForDepsOnDisk();
}
