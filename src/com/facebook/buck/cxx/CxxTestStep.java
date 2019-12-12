/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractTestStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Run a C/C++ test command, remembering it's exit code and streaming it's output to a given output
 * file.
 */
class CxxTestStep extends AbstractTestStep {

  private static final String NAME = "c++ test";

  public CxxTestStep(
      ProjectFilesystem filesystem,
      ImmutableList<String> command,
      ImmutableMap<String, String> env,
      Path exitCode,
      Path output,
      Optional<Long> testRuleTimeoutMs) {
    super(
        NAME,
        filesystem,
        Optional.empty(),
        command,
        Optional.of(env),
        exitCode,
        testRuleTimeoutMs,
        output);
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof CxxTestStep) && super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
