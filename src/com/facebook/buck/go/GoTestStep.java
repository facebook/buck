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

package com.facebook.buck.go;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractTestStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/** Run a go test command and stream the output to a file. */
public class GoTestStep extends AbstractTestStep {

  private static final String NAME = "go test";

  public GoTestStep(
      ProjectFilesystem filesystem,
      Path workingDirectory,
      ImmutableList<String> command,
      ImmutableMap<String, String> env,
      Path exitCode,
      Optional<Long> testRuleTimeoutMs,
      Path output) {
    super(
        NAME,
        filesystem,
        Optional.of(workingDirectory),
        command,
        Optional.of(env),
        exitCode,
        testRuleTimeoutMs,
        output);
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof GoTestStep) && super.equals(o);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
