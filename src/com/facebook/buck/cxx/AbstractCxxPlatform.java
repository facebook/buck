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

package com.facebook.buck.cxx;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;

import org.immutables.value.Value;

import java.util.List;

/**
 * Interface describing a C/C++ toolchain and platform to build for.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxPlatform implements FlavorConvertible {

  @Override
  public abstract Flavor getFlavor();

  public abstract Compiler getAs();
  public abstract List<String> getAsflags();

  public abstract Preprocessor getAspp();
  public abstract List<String> getAsppflags();

  protected abstract Supplier<Compiler> getCcSupplier();
  public abstract List<String> getCflags();
  public Compiler getCc() {
    return getCcSupplier().get();
  }

  protected abstract Supplier<Compiler> getCxxSupplier();
  public abstract List<String> getCxxflags();
  public Compiler getCxx() {
    return getCxxSupplier().get();
  }

  protected abstract Supplier<Preprocessor> getCppSupplier();
  public abstract List<String> getCppflags();
  public Preprocessor getCpp() {
    return getCppSupplier().get();
  }

  protected abstract Supplier<Preprocessor> getCxxppSupplier();
  public abstract List<String> getCxxppflags();
  public Preprocessor getCxxpp() {
    return getCxxppSupplier().get();
  }

  public abstract Optional<Preprocessor> getCudapp();
  public abstract List<String> getCudappflags();

  public abstract Optional<Compiler> getCuda();
  public abstract List<String> getCudaflags();

  public abstract Linker getLd();
  public abstract List<String> getLdflags();
  public abstract Multimap<Linker.LinkableDepType, String> getRuntimeLdflags();

  public abstract Tool getStrip();
  public abstract List<String> getStripFlags();

  public abstract Archiver getAr();
  public abstract List<String> getArflags();

  public abstract Tool getRanlib();
  public abstract List<String> getRanlibflags();

  public abstract SymbolNameTool getSymbolNameTool();

  public abstract String getSharedLibraryExtension();
  public abstract String getSharedLibraryVersionedExtensionFormat();

  public abstract DebugPathSanitizer getDebugPathSanitizer();

  /**
   * @return a map for macro names to their respective expansions, to be used to expand macro
   *     references in user-provided flags.
   */
  public abstract ImmutableMap<String, String> getFlagMacros();

}
