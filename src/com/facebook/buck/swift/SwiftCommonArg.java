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

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

/** Common argument for swift rules */
public interface SwiftCommonArg extends BuildRuleArg {
  Optional<String> getHeaderPathPrefix();

  Optional<String> getModuleName();

  ImmutableList<StringWithMacros> getSwiftCompilerFlags();

  Optional<String> getSwiftVersion();

  /**
   * When set the target will use explicit module compilation. All dependencies will be passed in
   * either with a Swift module map file for dependent swiftmodules or using
   * `-fmodule-file=<name>=<path>` for dependent Clang modules. The build rules to compile the
   * module outputs will be created for the SDK dependencies and the Clang module dependencies.
   *
   * <p>This should improve compilation time via sharing of cached module artifacts and reducing the
   * amount of time spent resolving headers and swiftmodule files from search paths.
   */
  @Value.Default
  default boolean getUsesExplicitModules() {
    return false;
  }

  @Value.Default
  default boolean getSerializeDebuggingOptions() {
    return true;
  }

  @Value.Default
  default boolean getEnableCxxInterop() {
    return false;
  }
}
