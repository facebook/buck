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

package com.facebook.buck.toolchain.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainDescriptor;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.ToolchainSupplier;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.pf4j.DefaultPluginManager;

public class DefaultToolchainProviderTest {

  private static final String MESSAGE = "something unexpected happened";

  public interface NoopToolchain extends Toolchain {
    String DEFAULT_NAME = "no-op-toolchain";
  }

  public static class ThrowingToolchainFactory implements ToolchainFactory<NoopToolchain> {

    @Override
    public Optional<NoopToolchain> createToolchain(
        ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
      throw new IllegalArgumentException(MESSAGE);
    }
  }

  @Test
  public void testExceptionMessageIsIncludedInThrownMessage() {

    ToolchainSupplier supplier =
        () ->
            Collections.singleton(
                ToolchainDescriptor.of(
                    NoopToolchain.DEFAULT_NAME,
                    NoopToolchain.class,
                    ThrowingToolchainFactory.class));

    DefaultToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            new DefaultPluginManager() {
              @Override
              public <T> List<T> getExtensions(Class<T> type) {
                if (ToolchainSupplier.class.equals(type)) {
                  return (List<T>) Collections.singletonList(supplier);
                }
                return super.getExtensions(type);
              }
            },
            ImmutableMap.of(),
            FakeBuckConfig.builder().build(),
            new FakeProjectFilesystem(),
            new FakeProcessExecutor(),
            new ExecutableFinder());

    try {
      toolchainProvider.getByName(NoopToolchain.DEFAULT_NAME);
      fail("Toolchain creation should fail");
    } catch (HumanReadableException e) {
      assertEquals(
          "Cannot create a toolchain: no-op-toolchain. Cause: something unexpected happened",
          e.getHumanReadableErrorMessage());
    }
  }
}
