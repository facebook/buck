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

/**
 * This strategy is responsible for detecting whether sandboxing is enabled and creating a {@link
 * ProgramRunner} to run a program in a sandbox mode.
 */
public interface SandboxExecutionStrategy {

  /** @return when sandboxing is supported by the platform and it's enabled */
  boolean isSandboxEnabled();

  /**
   * Creates a new {@link ProgramRunner} to run a specific program in a sandbox.
   *
   * @param sandboxProperties describes the particular execution of a sandboxed processes.
   */
  ProgramRunner createSandboxProgramRunner(SandboxProperties sandboxProperties);
}
