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

public enum CacheResultType {

    /** Artifact was successfully fetched from cache */
    HIT(/* success */ true),

    /** Artifact was missing from cache */
    MISS(/* success */ false),

    /** An error occurred when fetching artifact from cache*/
    ERROR(/* success */ false),

    /** Artifact cache not queried because some of the dependencies were re-built. */
    SKIP(/* success */ false),

    /** Artifact cache not queried because the local cache key was unchanged. */
    LOCAL_KEY_UNCHANGED_HIT(/* success */ true),

    ;

    private boolean success;

    CacheResultType(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }
}
