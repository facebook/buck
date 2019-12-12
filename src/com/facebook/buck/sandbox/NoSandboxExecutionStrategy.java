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

package com.facebook.buck.sandbox;

import com.facebook.buck.shell.programrunner.ProgramRunner;

/** A strategy that guarantees that sandboxing is not used. */
public class NoSandboxExecutionStrategy implements SandboxExecutionStrategy {
  @Override
  public boolean isSandboxEnabled() {
    return false;
  }

  @Override
  public ProgramRunner createSandboxProgramRunner(SandboxProperties sandboxProperties) {
    throw new IllegalStateException("Creating sandbox runner when sandbox is not present");
  }
}
