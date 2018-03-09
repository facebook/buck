/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules;

/**
 * Used for running a BuildExecutor within the context of the build engine such that the engine's
 * internal state/tracking is updated as expected. Provides ability to decide at runtime whether to
 * use custom BuildExecutor or just the default.
 */
public interface BuildExecutorRunner {
  void runWithDefaultExecutor();

  void runWithExecutor(BuildExecutor buildExecutor);
}
