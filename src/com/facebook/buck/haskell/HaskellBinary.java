/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import com.facebook.buck.rules.BinaryWrapperRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class HaskellBinary extends BinaryWrapperRule {

  private final ImmutableSet<BuildRule> deps;
  private final Tool binary;
  private final Path output;

  public HaskellBinary(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<BuildRule> deps,
      Tool binary,
      Path output) {
    super(buildRuleParams, ruleFinder);
    this.deps = deps;
    this.binary = binary;
    this.output = output;
  }

  @Override
  public Tool getExecutableCommand() {
    return binary;
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public ImmutableSet<BuildRule> getBinaryDeps() {
    return deps;
  }

}
