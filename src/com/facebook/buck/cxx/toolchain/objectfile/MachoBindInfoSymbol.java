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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import java.util.Optional;

/**
 * Defines a pair of symbol and optional library name which a Mach-O executable will bind at
 * runtime.
 */
@BuckStyleValue
public abstract class MachoBindInfoSymbol {
  /** Defines which image is used to look up a symbol. */
  public enum LibraryLookup {
    /** Library specified by {@link MachoBindInfoSymbol#getLibraryName()} */
    DEPENDENT_LIBRARY,
    /** Executable itself */
    SELF,
    /** Main executable */
    MAIN_EXECUTABLE,
    /** Flat lookup (i.e., first matching symbol at runtime). */
    FLAT_LOOKUP,
  }

  /** Returns the name of the bound symbol. */
  public abstract String getSymbolName();
  /**
   * Returns the library name for the bound symbol. If {@link
   * MachoBindInfoSymbol#getLibraryLookup()} returns {@link LibraryLookup#DEPENDENT_LIBRARY}, then
   * this method will return a non-empty string.
   */
  public abstract Optional<String> getLibraryName();
  /** Defines which image will be used for the symbol lookup. */
  public abstract LibraryLookup getLibraryLookup();

  /** Factory method */
  public static MachoBindInfoSymbol of(
      String symbolName, Optional<String> libraryName, LibraryLookup libraryLookup) {
    Preconditions.checkState(
        libraryLookup != LibraryLookup.FLAT_LOOKUP || !libraryName.isPresent(),
        "Cannot have flat lookup with a library name");
    return ImmutableMachoBindInfoSymbol.ofImpl(symbolName, libraryName, libraryLookup);
  }
}
