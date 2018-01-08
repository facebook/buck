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

/** Distributed build exit codes used by Stampede. */
public enum ExitCode {
  /** Forced coordinator shutdown */
  UNEXPECTED_STOP_EXIT_CODE(42),

  /** GetWorkRequest failed internally */
  GET_WORK_FAILED_EXIT_CODE(43),

  /** ReportMinionAliveRequest failed internally */
  I_AM_ALIVE_FAILED_EXIT_CODE(45),

  /** Coordinator found dead minion and failed the build */
  DEAD_MINION_FOUND_EXIT_CODE(46),

  /** Coordinator detected that build had been externally set to terminal state */
  BUILD_TERMINATED_REMOTELY_EXIT_CODE(47),

  /** Preparation step threw an Exception during sync steps */
  PREPARATION_STEP_FAILED(200),

  /** Preparation step threw an Exception during async steps */
  PREPARATION_ASYNC_STEP_FAILED(201),

  /** Distributed build failed because of a local Exception (e.g. failed to speak to frontend) */
  DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION(205),

  /** Distributed build failed because of a remote issue (e.g. minion died) */
  DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE(206);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
