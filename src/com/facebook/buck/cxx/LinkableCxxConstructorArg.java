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

package com.facebook.buck.cxx;

import com.facebook.buck.core.linkgroup.CxxLinkGroupMapping;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

public interface LinkableCxxConstructorArg extends CxxConstructorArg {
  Optional<Linker.LinkableDepType> getLinkStyle();

  /**
   * Defines the list of link group mappings. Targets' membership in a group is defined by the order
   * of the list, so if more than one mapping matches a single target, the group would be the one
   * defined by the first match.
   */
  Optional<ImmutableList<CxxLinkGroupMapping>> getLinkGroupMap();

  /**
   * Defines the link group. When linking executable code, only static libraries which belong to the
   * same group would be linked into the executable.
   */
  Optional<String> getLinkGroup();

  @Value.Default
  default boolean getThinLto() {
    return false;
  }

  @Value.Default
  default boolean getFatLto() {
    return false;
  }

}
