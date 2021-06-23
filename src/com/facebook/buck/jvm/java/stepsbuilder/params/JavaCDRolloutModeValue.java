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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.jvm.java.JavaCDRolloutMode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.immutables.value.Value;

/**
 * Wrapper around {@link JavaCDRolloutMode} that also exposes {@link #isFirstInvocation()} method.
 */
@BuckStyleValue
public abstract class JavaCDRolloutModeValue implements AddsToRuleKey {

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract JavaCDRolloutMode getJavacdMode();

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  @Value.Auxiliary // do not include this value into generated equals, hashCode and toString methods
  public abstract AtomicBoolean isFirstInvocation();

  public static JavaCDRolloutModeValue of(JavaCDRolloutMode javacdMode) {
    return ImmutableJavaCDRolloutModeValue.ofImpl(javacdMode, new AtomicBoolean(true));
  }
}
