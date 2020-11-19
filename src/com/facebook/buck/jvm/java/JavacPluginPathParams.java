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

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

/**
 * Plugin params whose values are paths that need to be resolved: SourcePaths and RelPaths.
 *
 * <p>For instance adding ("paramName", [target-source-path]) to params will result in adding {@code
 * -AparamName=[resolved-absolute-path]} to javac invocation.
 */
@BuckStyleValueWithBuilder
abstract class JavacPluginPathParams implements AddsToRuleKey {
  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableMap<String, SourcePath> getSourcePathParams();

  @Value.NaturalOrder
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public abstract ImmutableMap<String, RelPath> getRelPathParams();

  /** @return The total number of params. */
  public int getSize() {
    return getSourcePathParams().size() + getRelPathParams().size();
  }

  /** @return An empty path params. */
  public static JavacPluginPathParams empty() {
    return ImmutableJavacPluginPathParams.builder().build();
  }
}
