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

package com.facebook.buck.core.select.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.select.SelectableConfigurationContext;

/** Fake implementation which throws unconditionally */
public class ThrowingSelectableConfigurationContext implements SelectableConfigurationContext {

  @Override
  public BuckConfig getBuckConfig() {
    throw new IllegalStateException();
  }

  /** Throw unconditionally */
  @Override
  public SelectableConfigurationContext withPlatform(Platform platform) {
    throw new IllegalStateException();
  }

  @Override
  public Platform getPlatform() {
    throw new IllegalStateException();
  }
}
