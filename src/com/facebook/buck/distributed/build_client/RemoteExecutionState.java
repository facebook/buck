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

package com.facebook.buck.distributed.build_client;

/** State of the current remote execution unit. */
public enum RemoteExecutionState {
  ENQUEUED,
  REMOTE_BUILD_STARTED,
  REMOTE_BUILD_FINISHED,
  DOWNLOADING_RESULT,
  BUILD_FINISHED,
  BUILT_LOCALLY,
  BUILT_LOCALLY_BECAUSE_OF_CACHING_ISSUE,
}
