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

package com.facebook.buck.cxx.toolchain.objectfile;

import java.util.Optional;

/**
 * Represents the binding context for the Mach-O bind actions. The actions themselves implicitly
 * operate on a shared binding context to produce actions.
 *
 * <p>Note that the binding context here is *incomplete* as we're only interested in a subset of the
 * data. This can be expanded over time as needed.
 */
public class MachoBindContext {
  /** Defines the library ordinal, using 1-based indexing, in the list of dependent libraries. */
  public Optional<Integer> libraryOrdinal = Optional.empty();
  /** Defines the symbol name being bound. */
  public Optional<String> symbolName = Optional.empty();
}
