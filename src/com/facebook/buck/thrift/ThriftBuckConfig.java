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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

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

  public Optional<Path> getCompilerPath() {
    return delegate.getPath("thrift", "compiler_path");
  }

  public Optional<BuildTarget> getCompilerTarget() {
    return getBuildTarget("thrift", "compiler_target");
  }

  /**
   * Return the {@link SourcePath} object representing the thrift compiler.  This either wraps
   * a hard-coded path or a {@link BuildRule} which builds the compiler.
   */
  public SourcePath getCompiler(BuildRuleResolver resolver) {
    Optional<Path> compilerPath = getCompilerPath();
    Optional<BuildTarget> compilerTarget = getCompilerTarget();

    // The user must set either a compiler target of path.
    if (compilerTarget.isPresent() && compilerPath.isPresent()) {
      throw new HumanReadableException(
          "Cannot set both thrift:compiler_target and thrift:compiler_path");
    }

    SourcePath sourcePath;

    if (compilerTarget.isPresent()) {
      Optional<BuildRule> rule = resolver.getRuleOptional(compilerTarget.get());
      if (!rule.isPresent()) {
        throw new HumanReadableException(
            ".buckconfig: thrift:compiler_target rule \"%s\" does not exists",
            compilerTarget.get());
      }
      sourcePath = new BuildRuleSourcePath(rule.get());
    } else if (compilerPath.isPresent()) {
      sourcePath = new PathSourcePath(compilerPath.get());
    } else {
      throw new HumanReadableException(
          ".buckconfig: must set either thrift:compiler_target or thrift:compiler_path");
    }

    return sourcePath;
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
