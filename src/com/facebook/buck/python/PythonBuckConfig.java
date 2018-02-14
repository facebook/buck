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

package com.facebook.buck.python;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.tool.config.ToolConfig;
import java.util.Optional;
import java.util.stream.Stream;

public class PythonBuckConfig {

  public static final Flavor DEFAULT_PYTHON_PLATFORM = InternalFlavor.of("py-default");

  private static final String SECTION = "python";
  private static final String PYTHON_PLATFORM_SECTION_PREFIX = "python#";

  private final BuckConfig delegate;

  public PythonBuckConfig(BuckConfig config) {
    this.delegate = config;
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  public Optional<String> getInterpreter(String section) {
    return delegate.getValue(section, "interpreter");
  }

  public Optional<BuildTarget> getPexTarget() {
    return delegate.getMaybeBuildTarget(SECTION, "path_to_pex");
  }

  public String getPexFlags() {
    return delegate.getValue(SECTION, "pex_flags").orElse("");
  }

  public Optional<Tool> getRawPexTool(BuildRuleResolver resolver) {
    return delegate.getView(ToolConfig.class).getTool(SECTION, "path_to_pex", resolver);
  }

  public Optional<BuildTarget> getPexExecutorTarget() {
    return delegate.getMaybeBuildTarget(SECTION, "path_to_pex_executer");
  }

  public Optional<Tool> getPexExecutor(BuildRuleResolver resolver) {
    return delegate.getView(ToolConfig.class).getTool(SECTION, "path_to_pex_executer", resolver);
  }

  public NativeLinkStrategy getNativeLinkStrategy() {
    return delegate
        .getEnum(SECTION, "native_link_strategy", NativeLinkStrategy.class)
        .orElse(NativeLinkStrategy.SEPARATE);
  }

  public String getPexExtension() {
    return delegate.getValue(SECTION, "pex_extension").orElse(".pex");
  }

  public Optional<String> getConfiguredVersion(String section) {
    return delegate.getValue(section, "version");
  }

  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(SECTION, "cache_binaries", true);
  }

  public boolean legacyOutputPath() {
    return delegate.getBooleanValue(SECTION, "legacy_output_path", false);
  }

  public PackageStyle getPackageStyle() {
    return delegate
        .getEnum(SECTION, "package_style", PackageStyle.class)
        .orElse(PackageStyle.STANDALONE);
  }

  public String getDefaultPythonPlatformSection() {
    return SECTION;
  }

  public Flavor getDefaultPythonPlatformFlavor() {
    return DEFAULT_PYTHON_PLATFORM;
  }

  public Stream<String> getPythonPlatformSections() {
    return delegate
        .getSections()
        .stream()
        .filter(section -> section.startsWith(PYTHON_PLATFORM_SECTION_PREFIX));
  }

  public Flavor calculatePythonPlatformFlavorFromSection(String section) {
    return InternalFlavor.of(section.substring(PYTHON_PLATFORM_SECTION_PREFIX.length()));
  }

  public Optional<BuildTarget> getCxxLibrary(String section) {
    return delegate.getBuildTarget(section, "library");
  }

  public String getDefaultSection() {
    return SECTION;
  }

  public enum PackageStyle {
    STANDALONE,
    INPLACE,
  }
}
