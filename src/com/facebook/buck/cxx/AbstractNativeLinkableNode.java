/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
interface AbstractNativeLinkableNode {

  /**
   * Keeps track of the deps we're looking for.  This will initially begin out as {@code ANY},
   * but will transition to {@code SHARED_ONLY} whenever we cross a dep that require dynamic
   * linking, at which point we know to ignore any of it's statically linked deps.
   */
  enum Pass {
    ANY,
    SHARED_ONLY,
  }

  @Value.Parameter
  BuildRule getBuildRule();

  @Value.Parameter
  Pass getPass();

}
