/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.haskell;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/**
 * Information that contributes to a haskell compilation job. Dependencies (e.g. `haskell_library`
 * rules) export this object to their dependent's compilations.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractHaskellCompileInput {

  /** @return any haskell compilation flags to include in the top-level compile job. */
  ImmutableList<String> getFlags();

  /** @return any haskell includes used by the top-level compile job. */
  ImmutableList<SourcePath> getIncludes();

  /** @return any haskell packages used by the top-level compile job. */
  ImmutableList<HaskellPackage> getPackages();
}
