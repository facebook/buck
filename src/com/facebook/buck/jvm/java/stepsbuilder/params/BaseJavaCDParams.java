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

package com.facebook.buck.jvm.java.stepsbuilder.params;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** Base params related to javacd. */
@BuckStyleValue
public abstract class BaseJavaCDParams implements AddsToRuleKey {

  // TODO: msemko implement
  private static final boolean PIPELINING_SUPPORT_ENABLED = false;

  @AddToRuleKey
  public abstract boolean hasJavaCDEnabled();

  @AddToRuleKey
  @Value.Default
  boolean pipeliningEnabled() {
    // TODO: msemko remove this method when pipelining support is ready.
    return PIPELINING_SUPPORT_ENABLED;
  }

  /** Returns whether the pipelining case supported by javacd */
  @AddToRuleKey
  @Value.Derived
  public boolean pipeliningSupported() {
    // TODO: msemko inline this method when pipelining support is ready. Replace with
    // `hasJavaCDEnabled()`.
    return hasJavaCDEnabled() && pipeliningEnabled();
  }

  @ExcludeFromRuleKey(
      reason = "start javacd jvm options is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract ImmutableList<String> getStartCommandOptions();

  @ExcludeFromRuleKey(
      reason = "javacd worker tool pool size is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getWorkerToolPoolSize();

  @ExcludeFromRuleKey(
      reason = "javacd borrow from the pool is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getBorrowFromPoolTimeoutInSeconds();

  /** Creates {@link BaseJavaCDParams} */
  public static BaseJavaCDParams of(
      boolean hasJavaCDEnabled,
      Iterable<String> startCommandOptions,
      int workerToolPoolSize,
      int borrowFromPoolTimeoutInSeconds) {
    return ImmutableBaseJavaCDParams.ofImpl(
        hasJavaCDEnabled,
        PIPELINING_SUPPORT_ENABLED,
        startCommandOptions,
        workerToolPoolSize,
        borrowFromPoolTimeoutInSeconds);
  }
}
