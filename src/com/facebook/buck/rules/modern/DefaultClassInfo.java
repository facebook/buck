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

package com.facebook.buck.rules.modern;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// TODO(cjhopman): implement.
public class DefaultClassInfo<T extends Buildable> implements ClassInfo<T> {
  @Override
  public void computeDeps(
      T ruleImpl, InputRuleResolver inputRuleResolver, Consumer<BuildRule> depsBuilder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void appendToRuleKey(T ruleImpl, RuleKeyObjectSink sink) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void getOutputData(T ruleImpl, BiConsumer<String, OutputData> outputDataBuilder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void getOutputs(T ruleImpl, BiConsumer<String, OutputPath> dataBuilder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getType() {
    throw new UnsupportedOperationException();
  }

  public static <T extends Buildable> ClassInfo<T> from(T buildable) {
    buildable.getClass();
    return new DefaultClassInfo<>();
  }
}
