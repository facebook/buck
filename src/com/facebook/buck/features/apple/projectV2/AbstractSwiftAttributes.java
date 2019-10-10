/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Attributes that are specific to target's containing Swift code. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSwiftAttributes {

  /** The module name of the Swift target. */
  public abstract String moduleName();

  /** Swift compiler version that the target supports. */
  public abstract Optional<String> swiftVersion();

  /**
   * The generated bridging header name for Objectice C code. Typically the @{code
   * moduleName}-Swift.h.
   */
  public abstract String objCGeneratedHeaderName();

  /** Map of target relative generated bridging header path to absolute path in the filesystem. */
  public abstract ImmutableMap<Path, Path> publicHeaderMapEntries();
}
