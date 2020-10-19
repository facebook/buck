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

package com.facebook.buck.external.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import org.immutables.value.Value;

/** Buck config values for buildables V2. See https://fburl.com/buck1-buildables-v2. */
@BuckStyleValue
public abstract class ExternalActionsConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "buildables_v2";
  private static final String ENABLED_FIELD = "enabled";
  private static final boolean ENABLED_DEFAULT = false;

  @Override
  public abstract BuckConfig getDelegate();

  public static ExternalActionsConfig of(BuckConfig delegate) {
    return ImmutableExternalActionsConfig.ofImpl(delegate);
  }

  @Value.Lazy
  public boolean getEnabled() {
    return getDelegate().getBooleanValue(SECTION, ENABLED_FIELD, ENABLED_DEFAULT);
  }
}
