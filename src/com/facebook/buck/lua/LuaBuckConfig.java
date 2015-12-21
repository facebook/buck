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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.nio.file.Paths;

public class LuaBuckConfig implements LuaConfig {

  private static final String SECTION = "lua";

  private final BuckConfig delegate;
  private final ExecutableFinder finder;

  public LuaBuckConfig(
      BuckConfig delegate,
      ExecutableFinder finder) {
    this.delegate = delegate;
    this.finder = finder;
  }

  @VisibleForTesting
  protected Optional<Path> getSystemLua() {
    return finder.getOptionalExecutable(Paths.get("lua"), delegate.getEnvironment());
  }

  @Override
  public Tool getLua(BuildRuleResolver resolver) {
    Optional<Tool> configuredLua = delegate.getTool(SECTION, "lua", resolver);
    if (configuredLua.isPresent()) {
      return configuredLua.get();
    }

    Optional<Path> systemLua = getSystemLua();
    if (systemLua.isPresent()) {
      return new HashedFileTool(systemLua.get());
    }

    throw new HumanReadableException(
        "No lua interpreter found in .buckconfig (%s.lua) or on system",
        SECTION);
  }

  @Override
  public String getExtension() {
    return delegate.getValue(SECTION, "lua").or(".lex");
  }

}
