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

/**
 * The result of rule key computation, including the deps and inputs used to calculate the value.
 */
public class RuleKeyResult<R> {

  /** The result of rule key computation. */
  public final R result;

  /** All other `BuildRule`s and `RuleKeyAppendable`s which this rule key's value depends on. */
  public final Iterable<?> deps;

  /** All inputs this rule key's value depends on. */
  public final Iterable<RuleKeyInput> inputs;

  RuleKeyResult(R result, Iterable<?> deps, Iterable<RuleKeyInput> inputs) {
    this.result = result;
    this.deps = deps;
    this.inputs = inputs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    RuleKeyResult<?> that = (RuleKeyResult<?>) o;

    if (!result.equals(that.result)) {
      return false;
    }
    if (!deps.equals(that.deps)) {
      return false;
    }
    return inputs.equals(that.inputs);
  }

  @Override
  public int hashCode() {
    int result1 = result.hashCode();
    result1 = 31 * result1 + deps.hashCode();
    result1 = 31 * result1 + inputs.hashCode();
    return result1;
  }
}
