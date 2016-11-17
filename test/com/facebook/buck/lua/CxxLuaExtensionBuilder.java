/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.lua;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.AbstractCxxSourceBuilder;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.model.BuildTarget;

import java.util.Optional;

public class CxxLuaExtensionBuilder
    extends
    AbstractCxxSourceBuilder<
        CxxLuaExtensionDescription.Arg,
        CxxLuaExtensionDescription,
        CxxLuaExtensionBuilder> {

  public CxxLuaExtensionBuilder(
      CxxLuaExtensionDescription description,
      BuildTarget target) {
    super(description, target);
  }

  public CxxLuaExtensionBuilder(BuildTarget target, LuaConfig config) {
    this(
        new CxxLuaExtensionDescription(
            config,
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxPlatformUtils.DEFAULT_PLATFORMS),
        target);
  }

  public CxxLuaExtensionBuilder(BuildTarget target) {
    this(target, FakeLuaConfig.DEFAULT);
  }

  @Override
  protected CxxLuaExtensionBuilder getThis() {
    return this;
  }

  public CxxLuaExtensionBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

}
