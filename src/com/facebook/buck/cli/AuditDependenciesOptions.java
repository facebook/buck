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

package com.facebook.buck.cli;

import org.kohsuke.args4j.Option;


public class AuditDependenciesOptions extends AuditCommandOptions {

  @Option(
      name = "--include-tests",
      usage = "Includes a target's tests with its dependencies. With the transitive flag, this " +
          "prints the dependencies of the tests as well")
  private boolean includeTests = false;

  @Option(name = "--transitive",
      aliases = { "-t" },
      usage = "Whether to include transitive dependencies in the output")
  private boolean transitive = false;

  AuditDependenciesOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public boolean shouldShowTransitiveDependencies() {
    return transitive;
  }

  public boolean shouldIncludeTests() {
    return includeTests;
  }

}
