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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ClassInfo is used by ModernBuildRule to extract information from a Buildable instance. It
 * computes various things (rulekeys, deps, etc) by iterating over all the fields of the Buildable.
 */
public interface ClassInfo<T extends Buildable> {
  /** Computes the deps of ruleImpl and adds the to depsBuilder. */
  void computeDeps(
      T ruleImpl, InputRuleResolver inputRuleResolver, Consumer<BuildRule> depsBuilder);

  /** Adds all outputPaths in ruleImpl to the dataBuilder. */
  void getOutputs(T ruleImpl, BiConsumer<String, OutputPath> dataBuilder);

  /** Returns the rule typename for Buildables of this type. */
  String getType();
}
