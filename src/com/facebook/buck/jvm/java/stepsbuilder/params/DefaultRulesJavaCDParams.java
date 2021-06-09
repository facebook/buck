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

import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;

/** Default implementation of {@link RulesJavaCDParams} interface. */
@BuckStyleValue
public abstract class DefaultRulesJavaCDParams implements RulesJavaCDParams {

  @Override
  @ExcludeFromRuleKey(
      reason = "running with or without javacd should not be a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean hasJavaCDEnabled();

  @Override
  @ExcludeFromRuleKey(
      reason = "start javacd jvm options is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract ImmutableList<String> getStartCommandOptions();

  @Override
  @ExcludeFromRuleKey(
      reason = "javacd worker tool pool size is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getWorkerToolPoolSize();

  @Override
  @ExcludeFromRuleKey(
      reason = "javacd worker tool max instances size is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getWorkerToolMaxInstancesSize();

  @Override
  @ExcludeFromRuleKey(
      reason = "javacd borrow from the pool is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getBorrowFromPoolTimeoutInSeconds();

  @Override
  @ExcludeFromRuleKey(
      reason = "javacd max wait for the result is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getMaxWaitForResultTimeoutInSeconds();

  @Override
  @ExcludeFromRuleKey(
      reason = "pipelining disabled option is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean pipeliningDisabled();

  /** Creates {@link DefaultRulesJavaCDParams} */
  public static DefaultRulesJavaCDParams of(
      boolean hasJavaCDEnabled,
      Iterable<String> startCommandOptions,
      int workerToolPoolSize,
      int workerToolMaxInstancesSize,
      int borrowFromPoolTimeoutInSeconds,
      int maxWaitForResultTimeoutInSeconds,
      boolean pipeliningDisabled) {
    return ImmutableDefaultRulesJavaCDParams.ofImpl(
        hasJavaCDEnabled,
        startCommandOptions,
        workerToolPoolSize,
        workerToolMaxInstancesSize,
        borrowFromPoolTimeoutInSeconds,
        maxWaitForResultTimeoutInSeconds,
        pipeliningDisabled);
  }
}
