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

package com.facebook.buck.go;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class GoBuckConfig {

  private static final String SECTION = "go";

  private final BuckConfig delegate;

  public GoBuckConfig(final BuckConfig delegate) {
    this.delegate = delegate;
  }

  Optional<String> getDefaultPlatform() {
    return delegate.getValue(SECTION, "default_platform");
  }

  Optional<Path> getGoRoot() {
    return delegate.getPath(SECTION, "root");
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  public Optional<Path> getPath(String name) {
    return delegate.getPath(SECTION, name);
  }

  public Optional<String> getValue(String name) {
    return delegate.getValue(SECTION, name);
  }

  Path getDefaultPackageName(BuildTarget target) {
    Path prefix = Paths.get(delegate.getValue(SECTION, "prefix").orElse(""));
    return prefix.resolve(target.getBasePath());
  }

  ImmutableList<Path> getVendorPaths() {
    Optional<ImmutableList<String>> vendorPaths =
        delegate.getOptionalListWithoutComments(SECTION, "vendor_path", ':');

    if (vendorPaths.isPresent()) {
      return vendorPaths.get().stream().map(Paths::get).collect(ImmutableList.toImmutableList());
    }
    return ImmutableList.of();
  }

  Optional<Tool> getGoTestMainGenerator(BuildRuleResolver resolver) {
    return delegate.getView(ToolConfig.class).getTool(SECTION, "test_main_gen", resolver);
  }

  public Optional<Path> getTool() {
    return delegate.getPath(SECTION, "tool");
  }
}
