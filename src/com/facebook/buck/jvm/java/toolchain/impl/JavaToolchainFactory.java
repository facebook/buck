/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.toolchain.impl;

import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import java.util.Optional;

/** Creates the default, .buckconfig-based java toolchain. */
public class JavaToolchainFactory implements ToolchainFactory<JavaToolchain> {

  /** Creates a java toolchain from the .buckconfig. */
  @Override
  public Optional<JavaToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    return Optional.of(
        JavaToolchain.builder()
            .setJavacProvider(
                context
                    .getBuckConfig()
                    .getView(JavaBuckConfig.class)
                    .getJavacSpec(context.getTargetConfiguration().get())
                    .getJavacProvider())
            .build());
  }
}
