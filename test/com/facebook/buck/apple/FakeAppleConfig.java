/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.util.Console;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;

public class FakeAppleConfig extends AppleConfig {

  @Nullable
  private ImmutableMap<String, AppleSdkPaths> appleSdkPaths = null;

  public FakeAppleConfig() {
    super(new FakeBuckConfig());
  }

  public FakeAppleConfig setAppleSdkPaths(ImmutableMap<String, AppleSdkPaths> appleSdkPaths) {
    this.appleSdkPaths = appleSdkPaths;
    return this;
  }

  @Override
  public ImmutableMap<String, AppleSdkPaths> getAppleSdkPaths(Console console) {
    return Preconditions.checkNotNull(appleSdkPaths);
  }
}
