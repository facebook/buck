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

package com.facebook.buck.d;

import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;

/**
 * BinaryBuildRule implementation for D binaries.
 */
public class DBinary extends NoopBuildRule implements BinaryBuildRule {
  private final Tool executable;

  public DBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool executable) {
    super(params, resolver);
    this.executable = executable;
  }

  @Override
  public Tool getExecutableCommand() {
    return executable;
  }
}
