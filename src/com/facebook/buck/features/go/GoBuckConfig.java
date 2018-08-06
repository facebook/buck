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

package com.facebook.buck.features.go;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class GoBuckConfig {

  static final String SECTION = "go";
  private final BuckConfig delegate;

  private static final String VENDOR_PATH = "vendor_path";
  private static final String PROJECT_PATH = "project_path";
  private static final String TEST_MAIN_GEN = "test_main_gen";
  private static final String DEFAULT_PLATFORM = "default_platform";
  private static final String PREFIX = "prefix";

  public GoBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  /**
   * Get default Go platform
   *
   * @return Go platform
   */
  Optional<String> getDefaultPlatform() {
    return delegate.getValue(SECTION, DEFAULT_PLATFORM);
  }

  /**
   * Get package name either from build target attribute (package_name) if provided, otherwise use
   * BuildTarget base path.
   *
   * @return Package name
   */
  Path getDefaultPackageName(BuildTarget target) {
    Path prefix = Paths.get(delegate.getValue(SECTION, PREFIX).orElse(""));
    return prefix.resolve(target.getBasePath());
  }

  /**
   * Get vendor paths based on vendor_path section. Section allows to specify multiple vendor paths
   * separated by colon (":").
   *
   * @return List of vendor paths
   */
  ImmutableList<Path> getVendorPaths() {
    Optional<ImmutableList<String>> vendorPaths =
        delegate.getOptionalListWithoutComments(SECTION, VENDOR_PATH, ':');

    if (vendorPaths.isPresent()) {
      return vendorPaths.get().stream().map(Paths::get).collect(ImmutableList.toImmutableList());
    }
    return ImmutableList.of();
  }

  /**
   * Get test main generator. The tool is a middle-step utility that utilizes selected .go sources
   * and generates the main.go which is later on compiled and used as test binary (run by "buck
   * test").
   *
   * @return test_main_gen tool
   */
  Optional<Tool> getGoTestMainGenerator(BuildRuleResolver resolver) {
    return delegate.getView(ToolConfig.class).getTool(SECTION, TEST_MAIN_GEN, resolver);
  }

  /**
   * Get "project_path" location. When set, buck project will copy generated sources to given
   * directory
   *
   * @return project_path path
   */
  Optional<Path> getProjectPath() {
    Optional<String> path = delegate.getValue(SECTION, PROJECT_PATH);
    return (path.isPresent()) ? Optional.of(Paths.get(path.get())) : Optional.empty();
  }
}
