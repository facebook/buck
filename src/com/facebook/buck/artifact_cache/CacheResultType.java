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

package com.facebook.buck.artifact_cache;

import com.google.common.base.Verify;

public enum CacheResultType {

  /** Artifact was successfully fetched from cache */
  HIT(/* success */ true),

  /** Artifact was missing from cache */
  MISS(/* success */ false),

  /** An error occurred when fetching artifact from cache */
  ERROR(/* success */ false),

  /** The rule was uncachable */
  IGNORED(/* success */ false),

  /** The cache skipped checking this result */
  SKIPPED(/* success */ false),

  /** The cache contains this artifact, but was not fetched */
  CONTAINS(/* success */ true),

  /** Artifact cache not queried because the local cache key was unchanged. */
  LOCAL_KEY_UNCHANGED_HIT(/* success */ true),
  ;

  private boolean success;

  CacheResultType(boolean success) {
    this.success = success;
  }

  /** Whether the artifact was successfully fetched. */
  public boolean isSuccess() {
    return success;
  }

  public void verifyValidFinalType() {
    Verify.verify(this != SKIPPED, "SKIPPED is not a valid final cache result type.");
    Verify.verify(this != CONTAINS, "CONTAINS is not a valid final cache result type.");
  }
}
