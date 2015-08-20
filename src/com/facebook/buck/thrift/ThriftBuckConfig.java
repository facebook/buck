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
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Optional;

public class ThriftBuckConfig {

  private static final String SECTION = "thrift";
  private static final String COMPILER = "compiler";
  private static final String COMPILER2 = "compiler2";

  private final BuckConfig delegate;

  public ThriftBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * Return the {@link Tool} object representing the thrift compiler.  This either wraps a
   * hard-coded path or a {@link BuildTarget} which builds the compiler.
   */
  public Tool getCompiler(
      ThriftLibraryDescription.CompilerType compilerType,
      BuildRuleResolver resolver) {
    Optional<Tool> thrift2 = delegate.getTool(SECTION, COMPILER2, resolver);
    // If the thrift2 compiler option isn't set, fallback to the thrift1 compiler.
    if (compilerType == ThriftLibraryDescription.CompilerType.THRIFT2 && thrift2.isPresent()) {
      return thrift2.get();
    }
    return delegate.getRequiredTool(SECTION, COMPILER, resolver);
  }

  /**
   * @return the {@link BuildTarget} referring to the thrift compiler to use, if one was specified
   *     as a build target.
   */
  public Optional<BuildTarget> getCompilerTarget(
      ThriftLibraryDescription.CompilerType compilerType) {
    try {
      Optional<BuildTarget> thrift2 = delegate.getBuildTarget(SECTION, COMPILER2);
      if (compilerType == ThriftLibraryDescription.CompilerType.THRIFT2 && thrift2.isPresent()) {
        return thrift2;
      }
      return Optional.of(delegate.getRequiredBuildTarget(SECTION, COMPILER));
    } catch (BuildTargetParseException e) {
      return Optional.absent();
    }
  }

  public BuildTarget getJavaDep() {
    return delegate.getRequiredBuildTarget(SECTION, "java_library");
  }

  public BuildTarget getCppDep() {
    return delegate.getRequiredBuildTarget(SECTION, "cpp_library");
  }

  public BuildTarget getCpp2Dep() {
    return delegate.getRequiredBuildTarget(SECTION, "cpp2_library");
  }

  public BuildTarget getCppAyncDep() {
    return delegate.getRequiredBuildTarget(SECTION, "cpp_async_library");
  }

  public BuildTarget getCppReflectionDep() {
    return delegate.getRequiredBuildTarget(SECTION, "cpp_reflection_library");
  }

  public BuildTarget getCppFrozenDep() {
    return delegate.getRequiredBuildTarget(SECTION, "cpp_frozen_library");
  }

  public BuildTarget getCppJsonDep() {
    return delegate.getRequiredBuildTarget(SECTION, "cpp_json_library");
  }

  public BuildTarget getPythonDep() {
    return delegate.getRequiredBuildTarget(SECTION, "python_library");
  }

  public BuildTarget getPythonTwistedDep() {
    return delegate.getRequiredBuildTarget(SECTION, "python_twisted_library");
  }

}
