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
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;

/**
 * A class that wraps the input for a module compilation rule. This is used to combine the
 * SourcePath for the corresponding toolchain component with the relative path.
 */
@BuckStyleValue
abstract class ExplicitModuleInput implements AddsToRuleKey {
  /** The base path for this input. */
  @AddToRuleKey
  public abstract SourcePath getBasePath();

  /** The relative path for the modulemap or swiftinterface file. */
  @AddToRuleKey
  public abstract String getRelativePath();

  public String resolve(SourcePathResolverAdapter resolver) {
    return resolver.getIdeallyRelativePath(getBasePath()).resolve(getRelativePath()).toString();
  }

  static ExplicitModuleInput of(SourcePath basePath, Path relativePath) {
    return ImmutableExplicitModuleInput.ofImpl(basePath, relativePath.toString());
  }
}
