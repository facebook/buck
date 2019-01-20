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

package com.facebook.buck.core.toolchain;

import java.util.Collection;
import java.util.Optional;

/** An interface that give access to specific toolchains by toolchain name. */
public interface ToolchainProvider {

  /** @throws ToolchainInstantiationException when a toolchain cannot be created */
  Toolchain getByName(String toolchainName);

  /** @throws ToolchainInstantiationException when a toolchain cannot be created */
  <T extends Toolchain> T getByName(String toolchainName, Class<T> toolchainClass);

  <T extends Toolchain> Optional<T> getByNameIfPresent(
      String toolchainName, Class<T> toolchainClass);

  /** @return <code>true</code> if toolchain exists (triggering instantiation if needed) */
  boolean isToolchainPresent(String toolchainName);

  /**
   * @return <code>true</code> if toolchain has already been created (without triggering
   *     instantiation)
   */
  boolean isToolchainCreated(String toolchainName);

  /**
   * @return <code>true</code> if toolchain failed to instantiate (without triggering instantiation)
   */
  boolean isToolchainFailed(String toolchainName);

  /**
   * Provides access to all known toolchains that support the provided capability.
   *
   * <p>The toolchains are not created during the execution of this method.
   *
   * @return a collection of toolchain names that support the provided capability.
   */
  <T extends ToolchainWithCapability> Collection<String> getToolchainsWithCapability(
      Class<T> capability);

  /** @return the exception that was thrown during toolchain instantiation */
  Optional<ToolchainInstantiationException> getToolchainInstantiationException(
      String toolchainName);
}
