/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import org.immutables.value.Value;

public interface JavacPluginArgs extends CommonDescriptionArg, HasDeclaredDeps {

  /**
   * A value of false indicates that the plugin will not use a shared class loader to be loaded.
   * This should be true if the same instance of the plugin has not support to run concurrently
   * multiple times for different javac calls.
   *
   * <p>Defaults to false because that's the "safe" value and optimizes build time.
   */
  @Value.Default
  default boolean isIsolateClassLoader() {
    return false;
  }

  /**
   * A value of false indicates that the plugin either generates classes that are intended for use
   * outside of the code being processed or modifies bytecode in a way that modifies ABI. Plugins
   * that affect the ABI of the rule in which they run must be run during ABI generation from
   * source.
   *
   * <p>Defaults to false because that's the "safe" value. When migrating to ABI generation from
   * source, having as few ABI-affecting plugins as possible will yield the fastest ABI generation.
   */
  @Value.Default
  default boolean isDoesNotAffectAbi() {
    return false;
  }

  /**
   * If true, allows ABI-affecting plugins to run during ABI generation from source. To run during
   * ABI generation from source, a plugin must meet all of the following criteria:
   * <li>
   *
   *     <ul>
   *       Uses only the public APIs from JSR-269 (for annotation processing). Access to the
   *       Compiler Tree API may also be possible via a Buck support library.
   * </ul>
   *
   * <ul>
   *   Does not require details about types beyond those being compiled as a general rule. There are
   *   ways to ensure type information is available on a case by case basis, at some performance
   *   cost.
   * </ul>
   *
   * Defaults to false because that's the "safe" value. When migrating to ABI generation from
   * source, having as many ABI-affecting plugins as possible running during ABI generation will
   * result in the flattest build graph.
   */
  @Value.Default
  default boolean isSupportsAbiGenerationFromSource() {
    return false;
  }
}
