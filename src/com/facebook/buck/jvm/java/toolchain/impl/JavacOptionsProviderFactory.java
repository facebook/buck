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

package com.facebook.buck.jvm.java.toolchain.impl;

import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import java.util.Optional;

public class JavacOptionsProviderFactory implements ToolchainFactory<JavacOptionsProvider> {

  @Override
  public Optional<JavacOptionsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    JavaBuckConfig javaConfig = context.getBuckConfig().getView(JavaBuckConfig.class);
    JavacOptions defaultJavacOptions =
        javaConfig.getDefaultJavacOptions(context.getTargetConfiguration().get());

    return Optional.of(JavacOptionsProvider.of(defaultJavacOptions));
  }
}
