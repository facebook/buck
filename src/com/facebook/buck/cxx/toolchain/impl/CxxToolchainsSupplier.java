/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain.impl;

import com.facebook.buck.core.toolchain.ToolchainDescriptor;
import com.facebook.buck.core.toolchain.ToolchainSupplier;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProviderFactory;
import java.util.Collection;
import java.util.Collections;
import org.pf4j.Extension;

@Extension
public class CxxToolchainsSupplier implements ToolchainSupplier {

  @Override
  public Collection<ToolchainDescriptor<?>> getToolchainDescriptor() {
    return Collections.singleton(
        ToolchainDescriptor.of(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.class,
            CxxPlatformsProviderFactory.class));
  }
}
