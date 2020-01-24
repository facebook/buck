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

package com.facebook.buck.core.toolchain;

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Contains basic information about a {@link Toolchain} that can be used to identify and construct
 * an instance of a particular Toolchain.
 */
@BuckStyleValue
public interface ToolchainDescriptor<T extends Toolchain> {

  String getName();

  Class<T> getToolchainClass();

  Class<? extends ToolchainFactory<T>> getToolchainFactoryClass();

  static <T extends Toolchain> ToolchainDescriptor<T> of(
      String name,
      Class<T> toolchainClass,
      Class<? extends ToolchainFactory<T>> toolchainFactoryClass) {
    return ImmutableToolchainDescriptor.of(name, toolchainClass, toolchainFactoryClass);
  }
}
