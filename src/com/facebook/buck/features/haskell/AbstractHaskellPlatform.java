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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractHaskellPlatform implements FlavorConvertible {

  // TODO: For now, we rely on Haskell platforms having the same "name" as the C/C++ platforms they
  // wrap, due to having to lookup the Haskell platform in the C/C++ interfaces that Haskell rules
  // implement, into which only C/C++ platform objects are threaded.
  @Override
  public final Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }

  /** @return the {@link ToolProvider} for the haskell compiler. */
  abstract ToolProvider getCompiler();

  /** @return The Haddock binary. */
  abstract ToolProvider getHaddock();

  /** @return the {@link HaskellVersion} for the haskell compiler. */
  abstract HaskellVersion getHaskellVersion();

  /** @return a list of flags to use for compilation. */
  abstract ImmutableList<String> getCompilerFlags();

  /** @return the {@link ToolProvider} for the haskell linker. */
  abstract ToolProvider getLinker();

  /** @return a list of flags to use for linking. */
  abstract ImmutableList<String> getLinkerFlags();

  /** @return the {@link ToolProvider} for the haskell packager. */
  abstract ToolProvider getPackager();

  /** @return whether to cache haskell link rules. */
  abstract boolean shouldCacheLinks();

  /** @return whether to use the deprecated binary output location. */
  abstract Optional<Boolean> shouldUsedOldBinaryOutputLocation();

  /** @return The template to use for startup scripts for GHCi targets. */
  abstract Supplier<Path> getGhciScriptTemplate();

  /** @return The template to use for iserv scripts for GHCi targets. */
  abstract Supplier<Path> getGhciIservScriptTemplate();

  /** @return The binutils dir for GHCi targets. */
  abstract Supplier<Path> getGhciBinutils();

  /** @return The GHC binary for GHCi targets. */
  abstract Supplier<Path> getGhciGhc();

  /** @return The IServ binary for GHCi targets. */
  abstract Supplier<Path> getGhciIServ();

  /** @return The Profiled IServ binary for GHCi targets. */
  abstract Supplier<Path> getGhciIServProf();

  /** @return The lib dir for GHCi targets. */
  abstract Supplier<Path> getGhciLib();

  /** @return The C++ compiler for GHCi targets. */
  abstract Supplier<Path> getGhciCxx();

  /** @return The C compiler for GHCi targets. */
  abstract Supplier<Path> getGhciCc();

  /** @return The C preprocessor for GHCi targets. */
  abstract Supplier<Path> getGhciCpp();

  /** @return An optional prefix for generated Haskell package names. */
  abstract Optional<String> getPackageNamePrefix();

  /** @return The intended cxx link type, used for stub headers */
  abstract Optional<Linker.LinkableDepType> getLinkStyleForStubHeader();

  /** @return the {@link CxxPlatform} to use for C/C++ dependencies. */
  abstract CxxPlatform getCxxPlatform();
}
