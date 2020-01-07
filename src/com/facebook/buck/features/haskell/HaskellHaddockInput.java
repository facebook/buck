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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;

/**
 * Information that contributes to a haskell haddock job. Dependencies (e.g. `haskell_library`
 * rules) export this object to their dependent's compilations.
 */
@BuckStyleValue
interface HaskellHaddockInput {

  /** @return any haskell interfaces used */
  ImmutableSet<SourcePath> getInterfaces();

  /** @return any output directories used */
  ImmutableSet<SourcePath> getHaddockOutputDirs();
}
