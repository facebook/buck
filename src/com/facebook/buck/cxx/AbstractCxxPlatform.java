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
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;

import org.immutables.value.Value;

import java.util.List;

/**
 * Interface describing a C/C++ toolchain and platform to build for.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractCxxPlatform {

  Flavor getFlavor();

  Tool getAs();
  List<String> getAsflags();

  Tool getAspp();
  List<String> getAsppflags();

  Compiler getCc();
  List<String> getCflags();

  Compiler getCxx();
  List<String> getCxxflags();

  Tool getCpp();
  List<String> getCppflags();

  Tool getCxxpp();
  List<String> getCxxppflags();

  Linker getCxxld();
  List<String> getCxxldflags();

  Linker getLd();
  List<String> getLdflags();
  Multimap<Linker.LinkableDepType, String> getRuntimeLdflags();

  Tool getStrip();
  List<String> getStripFlags();

  Archiver getAr();
  List<String> getArflags();

  Optional<Tool> getLex();
  List<String> getLexFlags();

  Optional<Tool> getYacc();
  List<String> getYaccFlags();

  String getSharedLibraryExtension();

  DebugPathSanitizer getDebugPathSanitizer();

  /**
   * @return a map for macro names to their respective expansions, to be used to expand macro
   *     references in user-provided flags.
   */
  ImmutableMap<String, String> getFlagMacros();

}
