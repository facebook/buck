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

package com.facebook.buck.distributed;

/**
 * This defines the mode that the local Buck client will execute while running distributed builds.
 */
public enum DistLocalBuildMode {
  // Wait for the remote build to complete before proceeding to complete building locally.
  WAIT_FOR_REMOTE,
  // Do not wait for the remote build to complete before starting the local build.
  NO_WAIT_FOR_REMOTE,
  // Fire-up the remote build and exit local build.
  FIRE_AND_FORGET,
  // Checks that rule keys match on both the client that kicked off the build and at remote workers
  RULE_KEY_DIVERGENCE_CHECK,
}
