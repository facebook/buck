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
  /** Build finished successfully */
  SUCCESSFUL(0),

  /** Generic failure code for either local or remote build */
  FAILURE(1),

  /** Real exit code hasn't been generated yet */
  DISTRIBUTED_PENDING_EXIT_CODE(10),

  /** Real exit code hasn't been generated yet */
  LOCAL_PENDING_EXIT_CODE(11),

  /** Exception was thrown before local build finished */
  LOCAL_BUILD_EXCEPTION_CODE(12),

  /** Local build finished before the remote build */
  LOCAL_BUILD_FINISHED_FIRST(20),

  /** Forced coordinator shutdown */
  UNEXPECTED_STOP_EXIT_CODE(42),

  /** GetWorkRequest failed internally */
  GET_WORK_FAILED_EXIT_CODE(43),

  /** ReportMinionAliveRequest failed internally */
  I_AM_ALIVE_FAILED_EXIT_CODE(45),

  /** All minions in build are dead * */
  ALL_MINIONS_DEAD_EXIT_CODE(46),

  /** Coordinator detected that build had been externally set to terminal failed state */
  BUILD_FAILED_EXTERNALLY_EXIT_CODE(47),

  /** Preparation step threw an Exception during sync steps */
  PREPARATION_STEP_FAILED(200),

  /** Preparation step threw an Exception during async steps */
  PREPARATION_ASYNC_STEP_FAILED(201),

  /** Distributed build failed because of a local Exception (e.g. failed to speak to frontend) */
  DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION(205),

  /** Distributed build failed because of a remote issue (e.g. minion died) */
  DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE(206),

  /** Signals that rule keys were inconsistent between Stampede client and servers */
  RULE_KEY_CONSISTENCY_CHECK_FAILED(301);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
