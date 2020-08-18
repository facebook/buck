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

package com.facebook.buck.features.js;

import com.facebook.buck.core.config.BuckConfig;

/** JS specific buck config */
public class JsConfig {

  private static final String SECTION = "js";

  private final BuckConfig delegate;

  private JsConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public static JsConfig of(BuckConfig delegate) {
    return new JsConfig(delegate);
  }

  /**
   * Whether to treat unflavored js_library rules as dummy rules ( = not emit their js_file
   * dependencies).
   */
  public boolean getRequireTransformProfileFlavorForFiles() {
    return isEnabled("require_transform_profile_flavor_for_files");
  }

  private boolean isEnabled(String configKey) {
    return getDelegate().getBooleanValue(SECTION, configKey, false);
  }

  private BuckConfig getDelegate() {
    return delegate;
  }
}
