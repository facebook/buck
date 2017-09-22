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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractTestStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Runs a D test command, remembering its exit code and streaming its output to a given output file.
 */
public class DTestStep extends AbstractTestStep {

  private static final String NAME = "d test";

  public DTestStep(
      ProjectFilesystem filesystem,
      ImmutableList<String> command,
      Path exitCode,
      Optional<Long> testRuleTimeoutMs,
      Path output) {
    super(
        NAME,
        filesystem,
        Optional.empty(),
        command,
        Optional.empty(),
        exitCode,
        testRuleTimeoutMs,
        output);
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof DTestStep) && super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
