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

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractFakeLuaConfig implements LuaConfig {

  public static final FakeLuaConfig DEFAULT = FakeLuaConfig.builder().build();

  @Value.Default
  public Tool getLua() {
    return new CommandTool.Builder()
        .addArg("lua")
        .build();
  }

  @Override
  public Tool getLua(BuildRuleResolver resolver) {
    return getLua();
  }

  @Override
  @Value.Default
  public String getExtension() {
    return ".lex";
  }

}
