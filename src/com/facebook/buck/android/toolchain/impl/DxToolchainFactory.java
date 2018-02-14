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

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.DxConfig;
import com.facebook.buck.android.SmartDexingStep;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Optional;
import java.util.concurrent.Executors;

public class DxToolchainFactory implements ToolchainFactory<DxToolchain> {

  private static final Logger LOG = Logger.get(DxToolchainFactory.class);

  @Override
  public Optional<DxToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    JavaBuckConfig javaConfig = context.getBuckConfig().getView(JavaBuckConfig.class);

    if (javaConfig.getDxThreadCount().isPresent()) {
      LOG.warn("java.dx_threads has been deprecated. Use dx.max_threads instead");
    }

    DxConfig dxConfig = new DxConfig(context.getBuckConfig());

    ListeningExecutorService dxExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                dxConfig
                    .getDxMaxThreadCount()
                    .orElse(
                        javaConfig
                            .getDxThreadCount()
                            .orElse(SmartDexingStep.determineOptimalThreadCount())),
                new CommandThreadFactory("SmartDexing")));

    return Optional.of(DxToolchain.of(dxExecutorService));
  }
}
