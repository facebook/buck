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
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * Contains platform independent settings for C/C++ rules.
 */
public class CxxBuckConfig {

  private final BuckConfig delegate;

  public CxxBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return the {@link BuildTarget} which represents the lex library.
   */
  public BuildTarget getLexDep() {
    return delegate.getRequiredBuildTarget("cxx", "lex_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the python library.
   */
  public BuildTarget getPythonDep() {
    return delegate.getRequiredBuildTarget("cxx", "python_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the gtest library.
   */
  public BuildTarget getGtestDep() {
    return delegate.getRequiredBuildTarget("cxx", "gtest_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the boost testing library.
   */
  public BuildTarget getBoostTestDep() {
    return delegate.getRequiredBuildTarget("cxx", "boost_test_dep");
  }

  public Optional<Path> getPath(String flavor, String name) {
    return delegate
        .getPath("cxx", flavor + "_" + name)
        .or(delegate.getPath("cxx", name));
  }

  public <T extends Enum<T>> Optional<T> getLinkerType(String flavor, Class<T> clazz) {
    return delegate
        .getEnum("cxx", flavor + "_ld_type", clazz)
        .or(delegate.getEnum("cxx", "ld_type", clazz));
  }

  public Optional<ImmutableList<String>> getFlags(
      String field) {
    Optional<String> value = delegate.getValue("cxx", field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

}
