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
package com.facebook.buck.core.rules.analysis.impl;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.Provider;

public class FakeInfo implements InfoInterface {

  private final Provider provider;

  public FakeInfo(Provider provider) {
    this.provider = provider;
  }

  @Override
  public Location getCreationLoc() {
    return Location.BUILTIN;
  }

  @Override
  public Provider getProvider() {
    return provider;
  }
}
