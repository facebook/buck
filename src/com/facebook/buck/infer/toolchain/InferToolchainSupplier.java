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

package com.facebook.buck.infer.toolchain;

import com.facebook.buck.core.toolchain.ToolchainDescriptor;
import com.facebook.buck.core.toolchain.ToolchainSupplier;
import java.util.Collection;
import java.util.Collections;
import org.pf4j.Extension;

/** Adds {@link InferToolchain} to the list of toolchains descriptors via {@code @Extension}. */
@Extension
public class InferToolchainSupplier implements ToolchainSupplier {

  @Override
  public Collection<ToolchainDescriptor<?>> getToolchainDescriptor() {
    return Collections.singleton(
        ToolchainDescriptor.of(
            InferToolchain.DEFAULT_NAME, InferToolchain.class, InferToolchainFactory.class));
  }
}
