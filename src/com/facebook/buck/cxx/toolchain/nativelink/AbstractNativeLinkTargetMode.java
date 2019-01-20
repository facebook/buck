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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.google.common.base.Preconditions;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractNativeLinkTargetMode {

  /** @return the link type. */
  abstract Linker.LinkType getType();

  /** @return the name of the library, if applicable. */
  abstract Optional<String> getLibraryName();

  @Value.Check
  public void check() {
    Preconditions.checkState(
        getType() == Linker.LinkType.SHARED || getType() == Linker.LinkType.EXECUTABLE);
    Preconditions.checkState(!getLibraryName().isPresent() || getType() == Linker.LinkType.SHARED);
  }

  public static NativeLinkTargetMode executable() {
    return NativeLinkTargetMode.of(Linker.LinkType.EXECUTABLE, Optional.empty());
  }

  public static NativeLinkTargetMode library(Optional<String> soname) {
    return NativeLinkTargetMode.of(Linker.LinkType.SHARED, soname);
  }

  public static NativeLinkTargetMode library(String soname) {
    return library(Optional.of(soname));
  }

  public static NativeLinkTargetMode library() {
    return library(Optional.empty());
  }
}
