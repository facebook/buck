/*
 * Copyright 2019-present Facebook, Inc.
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

/** Tags objects that can provide a {@link CxxPlatform}. */
public interface ProvidesCxxPlatform {

  /**
   * Creates a {@link CxxPlatform} with this flavor. The flavor isn't logically part of the
   * CxxPlatform and long-term it's going away and so we don't consider it part of
   * ProvidesCxxPlatform and we require it to be provided by someone else.
   */
  CxxPlatform getPlatformWithFlavor(Flavor flavor);
}
