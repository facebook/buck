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

package com.facebook.buck.thrift;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

public class ThriftBuckConfig {

  private final BuckConfig delegate;

  public ThriftBuckConfig(BuckConfig delegate) {
    this.delegate = Preconditions.checkNotNull(delegate);
  }

  private Optional<BuildTarget> getBuildTarget(String section, String field) {
    Optional<String> target = delegate.getValue(section, field);
    return target.isPresent() ?
        Optional.of(delegate.getBuildTargetForFullyQualifiedTarget(target.get())) :
        Optional.<BuildTarget>absent();
  }

  private BuildTarget getRequiredBuildTarget(String section, String field) {
    Optional<BuildTarget> target = getBuildTarget(section, field);
    if (!target.isPresent()) {
      throw new HumanReadableException(String.format(
          ".buckconfig: %s:%s must be set",
          section,
          field));
    }
    return target.get();
  }

  /**
   * Return the {@link SourcePath} object representing the thrift compiler.  This either wraps
   * a hard-coded path or a {@link BuildTarget} which builds the compiler.
   */
  public SourcePath getCompiler() {
    return delegate.getRequiredSourcePath("thrift", "compiler");
  }

  public BuildTarget getJavaDep() {
    return getRequiredBuildTarget("thrift", "java_library");
  }

  public BuildTarget getCppDep() {
    return getRequiredBuildTarget("thrift", "cpp_library");
  }

  public BuildTarget getCpp2Dep() {
    return getRequiredBuildTarget("thrift", "cpp2_library");
  }

  public BuildTarget getCppAyncDep() {
    return getRequiredBuildTarget("thrift", "cpp_async_library");
  }

  public BuildTarget getCppReflectionDep() {
    return getRequiredBuildTarget("thrift", "cpp_reflection_library");
  }

  public BuildTarget getCppFrozenDep() {
    return getRequiredBuildTarget("thrift", "cpp_frozen_library");
  }

  public BuildTarget getCppJsonDep() {
    return getRequiredBuildTarget("thrift", "cpp_json_library");
  }

  public BuildTarget getPythonDep() {
    return getRequiredBuildTarget("thrift", "python_library");
  }

  public BuildTarget getPythonTwistedDep() {
    return getRequiredBuildTarget("thrift", "python_twisted_library");
  }

}
