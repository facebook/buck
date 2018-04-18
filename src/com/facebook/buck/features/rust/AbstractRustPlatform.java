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

package com.facebook.buck.features.rust;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import org.immutables.value.Value;

/** Abstracting the tooling/flags/libraries used to build Rust rules. */
@Value.Immutable
@BuckStyleTuple
interface AbstractRustPlatform extends FlavorConvertible {

  // TODO: For now, we rely on Rust platforms having the same "name" as the C/C++ platforms they
  // wrap, due to having to lookup the Rust platform in the C/C++ interfaces that Rust rules
  // implement, into which only C/C++ platform objects are threaded.
  @Override
  default Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }

  /** @return the {@link CxxPlatform} to use for C/C++ dependencies. */
  CxxPlatform getCxxPlatform();
}
