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

package com.facebook.buck.core.toolchain.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainDescriptor;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainSupplier;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.DefaultPluginManager;

public class DefaultToolchainProviderTest {

  private static final String MESSAGE = "something unexpected happened";

  public interface NoopToolchain extends Toolchain {
    String DEFAULT_NAME = "no-op-toolchain";

    @Override
    default String getName() {
      return DEFAULT_NAME;
    }
  }

  public static class ThrowingToolchainFactory implements ToolchainFactory<NoopToolchain> {

    private final RuntimeException exception;

    public ThrowingToolchainFactory(RuntimeException exception) {
      this.exception = exception;
    }

    @Override
    public Optional<NoopToolchain> createToolchain(
        ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
      throw exception;
    }
  }

  public static class ToolchainFactoryThrowingToolchainInstantiationException
      extends ThrowingToolchainFactory {
    public ToolchainFactoryThrowingToolchainInstantiationException() {
      super(new ToolchainInstantiationException(MESSAGE));
    }
  }

  public static class ToolchainFactoryThrowingIllegalStateException
      extends ThrowingToolchainFactory {
    public ToolchainFactoryThrowingIllegalStateException() {
      super(new IllegalStateException(MESSAGE));
    }
  }

  private <T extends ThrowingToolchainFactory> DefaultToolchainProvider createProvider(
      Class<T> factoryClass) {
    ToolchainSupplier supplier =
        () ->
            Collections.singleton(
                ToolchainDescriptor.of(
                    NoopToolchain.DEFAULT_NAME, NoopToolchain.class, factoryClass));

    return new DefaultToolchainProvider(
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
        new ExecutableFinder(),
        TestRuleKeyConfigurationFactory.create());
  }

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testExceptionMessageIsIncludedInThrownMessage() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    thrown.expect(ToolchainInstantiationException.class);
    thrown.expectMessage("something unexpected happened");

    toolchainProvider.getByName(NoopToolchain.DEFAULT_NAME);
  }

  @Test
  public void testTheSameExceptionThrown() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    ToolchainInstantiationException exception = null;
    try {
      toolchainProvider.getByName(NoopToolchain.DEFAULT_NAME);
      fail("Toolchain creation should fail");
    } catch (ToolchainInstantiationException e) {
      exception = e;
    }

    try {
      toolchainProvider.getByName(NoopToolchain.DEFAULT_NAME);
      fail("Toolchain creation should fail");
    } catch (ToolchainInstantiationException e) {
      assertSame(exception, e);
    }
  }

  @Test
  public void testRuntimeExceptionMessageIsIncludedInThrownMessage() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingIllegalStateException.class);

    thrown.expect(RuntimeException.class);
    thrown.expectMessage(
        "Cannot create a toolchain: no-op-toolchain. " + "Cause: something unexpected happened");

    toolchainProvider.getByName(NoopToolchain.DEFAULT_NAME);
  }

  @Test
  public void testExceptionNotThrownWhenAskingForPresence() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    assertFalse(toolchainProvider.isToolchainPresent(NoopToolchain.DEFAULT_NAME));
  }

  @Test
  public void testExceptionNotThrownWhenAskingIfCreated() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    toolchainProvider.getByNameIfPresent(NoopToolchain.DEFAULT_NAME, NoopToolchain.class);

    assertFalse(toolchainProvider.isToolchainCreated(NoopToolchain.DEFAULT_NAME));
  }

  @Test
  public void testAskingIfCreatedBeforeCreation() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    assertFalse(toolchainProvider.isToolchainCreated(NoopToolchain.DEFAULT_NAME));
  }

  @Test
  public void testExceptionNotThrownWhenAskingIfFailed() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    toolchainProvider.getByNameIfPresent(NoopToolchain.DEFAULT_NAME, NoopToolchain.class);

    assertTrue(toolchainProvider.isToolchainFailed(NoopToolchain.DEFAULT_NAME));
  }

  @Test
  public void testAskingIfFailedBeforeCreation() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    assertFalse(toolchainProvider.isToolchainFailed(NoopToolchain.DEFAULT_NAME));
  }

  @Test
  public void testExceptionNotThrownWhenConditionallyRequestingToolchain() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    assertFalse(
        toolchainProvider
            .getByNameIfPresent(NoopToolchain.DEFAULT_NAME, NoopToolchain.class)
            .isPresent());
  }

  @Test
  public void testGettingException() {
    DefaultToolchainProvider toolchainProvider =
        createProvider(ToolchainFactoryThrowingToolchainInstantiationException.class);

    toolchainProvider.getByNameIfPresent(NoopToolchain.DEFAULT_NAME, NoopToolchain.class);

    Optional<ToolchainInstantiationException> exception =
        toolchainProvider.getToolchainInstantiationException(NoopToolchain.DEFAULT_NAME);

    assertTrue(exception.isPresent());
    assertEquals(MESSAGE, exception.get().getHumanReadableErrorMessage());
  }
}
