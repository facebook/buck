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

package com.facebook.buck.features.rust;

import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.versions.HasVersionUniverse;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

/** Common parameters for linkable Rust rules */
public interface RustLinkableArgs extends RustCommonArgs, HasVersionUniverse {
  ImmutableList<StringWithMacros> getLinkerFlags();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformLinkerFlags() {
    return PatternMatchedCollection.of();
  }

  Optional<Linker.LinkableDepType> getLinkStyle();

  @Value.Default
  default boolean isRpath() {
    return true;
  }

  @Value.Default
  default boolean isFramework() {
    return true;
  }
}
