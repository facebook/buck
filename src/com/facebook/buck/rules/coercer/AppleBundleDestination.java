/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
public interface AppleBundleDestination {
  public enum SubfolderSpec {
    ABSOLUTE,
    WRAPPER,
    EXECUTABLES,
    RESOURCES,
    FRAMEWORKS,
    SHARED_FRAMEWORKS,
    SHARED_SUPPORT,
    PLUGINS,
    JAVA_RESOURCES,
    PRODUCTS,
  }

  @Value.Parameter
  SubfolderSpec getSubfolderSpec();

  @Value.Parameter
  Optional<String> getSubpath();

}
