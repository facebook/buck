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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDRolloutModeValue;
import com.facebook.buck.jvm.java.stepsbuilder.params.RulesJavaCDParams;
import com.google.common.collect.ImmutableList;

/**
 * Broken implementation of {@link RulesJavaCDParams} that is missing an annotation for one field.
 * Used in test {@link DefaultJavaLibraryTest#testSerializationFailure()}
 */
@BuckStyleValue
public abstract class BrokenJavaCDParams implements RulesJavaCDParams {

  @Override
  @AddToRuleKey
  public abstract boolean hasJavaCDEnabled();

  @Override
  @AddToRuleKey
  public abstract JavaCDRolloutModeValue getJavaCDRolloutModeValue();

  @Override
  @AddToRuleKey
  public abstract ImmutableList<String> getStartCommandOptions();

  @Override
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getWorkerToolPoolSize();

  @Override
  @AddToRuleKey
  public abstract int getWorkerToolMaxInstancesSize();

  @Override
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getBorrowFromPoolTimeoutInSeconds();

  @Override
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract int getMaxWaitForResultTimeoutInSeconds();

  @Override
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean pipeliningDisabled();

  @Override
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract boolean isIncludeAllBucksEnvVariables();

  public abstract boolean getParamWithNoAnnotation();

  public static BrokenJavaCDParams of() {
    return ImmutableBrokenJavaCDParams.ofImpl(
        false,
        JavaCDRolloutModeValue.of(JavaCDRolloutMode.UNKNOWN),
        ImmutableList.of(),
        1,
        1,
        1,
        1,
        false,
        false,
        false);
  }
}
