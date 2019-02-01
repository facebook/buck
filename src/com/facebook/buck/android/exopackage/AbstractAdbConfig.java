/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.android.exopackage;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

@BuckStyleTuple
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractAdbConfig implements ConfigView<BuckConfig> {
  @Override
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public boolean getRestartAdbOnFailure() {
    return Boolean.parseBoolean(
        getDelegate().getValue("adb", "adb_restart_on_failure").orElse("true"));
  }

  @Value.Lazy
  public ImmutableList<String> getAdbRapidInstallTypes() {
    return getDelegate().getListWithoutComments("adb", "rapid_install_types_beta");
  }

  @Value.Lazy
  public boolean getMultiInstallMode() {
    return getDelegate().getBooleanValue("adb", "multi_install_mode", false);
  }
}
