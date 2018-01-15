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

package com.facebook.buck.sandbox;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Config section responsible for sandbox features. */
@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
public abstract class AbstractSandboxConfig implements ConfigView<BuckConfig> {

  private static final String SANDBOX_CONFIG_SECTION = "sandbox";

  /**
   * Whether `genrule` should use sandboxing.
   *
   * <p>`genrule` sandboxing can be enabled or disabled for particular targets using {@code
   * enable_sandbox} parameter.
   */
  public boolean isGenruleSandboxEnabled() {
    return getDelegate().getBooleanValue(SANDBOX_CONFIG_SECTION, "genrule_sandbox_enabled", false);
  }

  /** Whether sandboxing is enabled on Darwin (OS X). */
  public boolean isDarwinSandboxEnabled() {
    return getDelegate().getBooleanValue(SANDBOX_CONFIG_SECTION, "darwin_sandbox_enabled", false);
  }

  /** Whether sandboxing is enabled on the current platform. */
  public boolean isSandboxEnabledForCurrentPlatform() {
    Platform platform = Platform.detect();
    if (platform == Platform.MACOS) {
      return isDarwinSandboxEnabled();
    }
    return false;
  }
}
