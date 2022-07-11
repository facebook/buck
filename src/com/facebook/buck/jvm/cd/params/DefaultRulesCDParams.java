/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.cd.params;

import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.Collections;

/** Default implementation of {@link RulesCDParams} interface. */
@BuckStyleValue
public abstract class DefaultRulesCDParams implements RulesCDParams {

  @Override
  @ExcludeFromRuleKey(
      reason = "running with or without compiler daemon should not be a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean isEnabled();

  @Override
  @ExcludeFromRuleKey(
      reason = "start compiler daemon jvm options is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract ImmutableList<String> getStartCommandOptions();

  @Override
  @ExcludeFromRuleKey(
      reason = "worker tool pool size is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getWorkerToolPoolSize();

  @Override
  @ExcludeFromRuleKey(
      reason = "worker tool max instances size is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getWorkerToolMaxInstancesSize();

  @Override
  @ExcludeFromRuleKey(
      reason = "borrow from the pool is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getBorrowFromPoolTimeoutInSeconds();

  @Override
  @ExcludeFromRuleKey(
      reason = "max wait for the result is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getMaxWaitForResultTimeoutInSeconds();

  @Override
  @ExcludeFromRuleKey(
      reason = "pipelining disabled option is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean pipeliningDisabled();

  @Override
  @ExcludeFromRuleKey(
      reason = "env variables option is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean isIncludeAllBucksEnvVariables();

  /** Creates {@link DefaultRulesCDParams} */
  public static DefaultRulesCDParams of(
      boolean hasCDEnabled,
      Iterable<String> startCommandOptions,
      int workerToolPoolSize,
      int workerToolMaxInstancesSize,
      int borrowFromPoolTimeoutInSeconds,
      int maxWaitForResultTimeoutInSeconds,
      boolean pipeliningDisabled,
      boolean includeAllBucksEnvVariables) {
    return ImmutableDefaultRulesCDParams.ofImpl(
        hasCDEnabled,
        startCommandOptions,
        workerToolPoolSize,
        workerToolMaxInstancesSize,
        borrowFromPoolTimeoutInSeconds,
        maxWaitForResultTimeoutInSeconds,
        pipeliningDisabled,
        includeAllBucksEnvVariables);
  }

  /** {@link RulesCDParams} null object to use with languages that support Compiler Daemons. */
  public static final DefaultRulesCDParams DISABLED =
      DefaultRulesCDParams.of(false, Collections.emptyList(), 0, 0, 0, 0, true, false);
}
