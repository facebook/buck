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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class ThriftSource {

  private final String name;
  private final BuildTarget compileTarget;
  private final ImmutableList<String> services;

  public ThriftSource(
      String name,
      BuildTarget compileTarget,
      ImmutableList<String> services) {
    this.name = name;
    this.compileTarget = compileTarget;
    this.services = services;
  }

  BuildTarget getCompileTarget() {
    return compileTarget;
  }

  public ImmutableList<String> getServices() {
    return services;
  }

  public Path getOutputDir(BuildRuleResolver ruleResolver) {
    BuildRule rule = ruleResolver.getRule(compileTarget);
    return ThriftLibraryDescription.getThriftCompilerOutputDir(
        rule.getProjectFilesystem(),
        compileTarget,
        name);
  }

}
