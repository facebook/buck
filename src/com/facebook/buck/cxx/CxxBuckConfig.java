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

package com.facebook.buck.cxx;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * Contains platform independent settings for C/C++ rules.
 */
public class CxxBuckConfig {

  private static final String FLAVORED_CXX_SECTION_PREFIX = "cxx#";
  private static final String UNFLAVORED_CXX_SECTION_PREFIX = "cxx";

  private final BuckConfig delegate;
  private final String cxxSection;

  /**
   * Constructs set of flavors given in a .buckconfig file, as is specified by section names
   * of the form cxx#{flavor name}.
   */
  public static ImmutableSet<Flavor> getCxxFlavors(BuckConfig config) {
    ImmutableSet.Builder<Flavor> builder = ImmutableSet.builder();
    ImmutableSet<String> sections = config.getSections();
    for (String section: sections) {
      if (section.startsWith(FLAVORED_CXX_SECTION_PREFIX)) {
        builder.add(ImmutableFlavor.of(section.substring(FLAVORED_CXX_SECTION_PREFIX.length())));
      }
    }
    return builder.build();
  }

  public CxxBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.cxxSection = UNFLAVORED_CXX_SECTION_PREFIX;
  }

  /*
   * A special constructor for a section of the form cxx#{flavor name}
   * which represents a generated flavor that uses the cxx options defined
   * in that section.
   */
  public CxxBuckConfig(BuckConfig delegate, Flavor flavor) {
    this.delegate = delegate;
    this.cxxSection = FLAVORED_CXX_SECTION_PREFIX + flavor.getName();
  }

  /**
   * @return the {@link BuildTarget} which represents the lex library.
   */
  public BuildTarget getLexDep() {
    return delegate.getRequiredBuildTarget(cxxSection, "lex_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the python library.
   */
  public BuildTarget getPythonDep() {
    return delegate.getRequiredBuildTarget(cxxSection, "python_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the gtest library.
   */
  public BuildTarget getGtestDep() {
    return delegate.getRequiredBuildTarget(cxxSection, "gtest_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the boost testing library.
   */
  public BuildTarget getBoostTestDep() {
    return delegate.getRequiredBuildTarget(cxxSection, "boost_test_dep");
  }

  public Optional<Path> getPath(String flavor, String name) {
    return delegate
        .getPath(cxxSection, flavor + "_" + name)
        .or(delegate.getPath(cxxSection, name));
  }

  public Optional<String> getDefaultPlatform() {
    return delegate.getValue(cxxSection, "default_platform");
  }

  public Optional<ImmutableList<String>> getFlags(
      String field) {
    Optional<String> value = delegate.getValue(cxxSection, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

  public CxxPreprocessMode getPreprocessMode() {
    Optional<CxxPreprocessMode> setting = delegate.getEnum(
      cxxSection, "preprocess_mode", CxxPreprocessMode.class);

    if (setting.isPresent()) {
      return setting.get();
    }

    // Support legacy configuration option
    return delegate.getBooleanValue(
            cxxSection,
            "combined_preprocess_and_compile",
            /* default*/ false)
        ? CxxPreprocessMode.COMBINED
        : CxxPreprocessMode.SEPARATE;
  }

  /*
   * Constructs the appropriate Linker for the specified platform.
   */
  public Optional<Linker> getLinker(Tool ld) {
    Optional<Platform> linkerPlatform = delegate
        .getEnum(cxxSection, "linker_platform", Platform.class);
    if (!linkerPlatform.isPresent()) {
      return Optional.absent();
    }
    Linker result;
    switch (linkerPlatform.get()) {
      case MACOS:
        result = new DarwinLinker(ld);
        break;
      case LINUX:
      case WINDOWS:
        result = new GnuLinker(ld);
        break;
      case UNKNOWN:
      default:
        throw new RuntimeException(
            "Invalid platform for linker. Must be one of {MACOS, LINUX, WINDOWS}");
    }
    return Optional.of(result);
  }
}
