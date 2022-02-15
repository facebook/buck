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

package com.facebook.buck.swift;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * A class that wraps the output from a module compilation rule. This is used to determine if we
 * need to pass this as a Swift or a Clang module.
 */
@BuckStyleValue
abstract class ExplicitModuleOutput implements AddsToRuleKey {
  /** The name of the module. */
  @AddToRuleKey
  public abstract String getName();

  /** If this output is a .swiftmodule or a .pcm file. */
  @AddToRuleKey
  public abstract boolean getIsSwiftmodule();

  /** The path for this output. */
  @AddToRuleKey
  public abstract SourcePath getOutputPath();

  static ExplicitModuleOutput of(String name, boolean isSwiftModule, SourcePath outputPath) {
    return ImmutableExplicitModuleOutput.ofImpl(name, isSwiftModule, outputPath);
  }
}
