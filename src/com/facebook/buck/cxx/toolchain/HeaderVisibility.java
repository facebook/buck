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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.InternalFlavor;

public enum HeaderVisibility implements FlavorConvertible {
  PUBLIC(InternalFlavor.of("public-header-visibility")),
  PRIVATE(InternalFlavor.of("private-header-visibility")),
  ;

  private final Flavor flavor;

  HeaderVisibility(Flavor flavor) {
    this.flavor = flavor;
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }
}
