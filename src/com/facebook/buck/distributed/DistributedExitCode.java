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

import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableSet;

/** Distributed build exit codes used by Stampede. */
public enum DistributedExitCode {
  /** Build finished successfully */
  SUCCESSFUL(0),

  /** Generic failure code for either local or remote build */
  FAILURE(1),

  /** Real exit code hasn't been generated yet */
  DISTRIBUTED_PENDING_EXIT_CODE(2010),

  /** Local build finished before the remote build */
  LOCAL_BUILD_FINISHED_FIRST(2020),

  /** Forced coordinator shutdown */
  UNEXPECTED_STOP_EXIT_CODE(2042),

  /** GetWorkRequest failed internally */
  GET_WORK_FAILED_EXIT_CODE(2043),

  /** ReportMinionAliveRequest failed internally */
  I_AM_ALIVE_FAILED_EXIT_CODE(2045),

  /** All minions in build are dead * */
  ALL_MINIONS_DEAD_EXIT_CODE(2046),

  /** Coordinator detected that build had been externally set to terminal failed state */
  BUILD_FAILED_EXTERNALLY_EXIT_CODE(2047),

  /** Preparation step threw an Exception during sync steps */
  PREPARATION_STEP_FAILED(2060),

  /** Preparation step threw an Exception during async steps */
  PREPARATION_ASYNC_STEP_FAILED(2061),

  /** Distributed build failed because of a local Exception (e.g. failed to speak to frontend) */
  DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION(2062),

  /** Distributed build failed because of a remote issue (e.g. minion died) */
  DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE(2063),

  /** Signals that rule keys were inconsistent between Stampede client and servers */
  RULE_KEY_CONSISTENCY_CHECK_FAILED(2064);

  private final int code;

  static final ImmutableSet<DistributedExitCode> INFRA_ERROR_CODES =
      ImmutableSet.of(
          UNEXPECTED_STOP_EXIT_CODE,
          GET_WORK_FAILED_EXIT_CODE,
          I_AM_ALIVE_FAILED_EXIT_CODE,
          ALL_MINIONS_DEAD_EXIT_CODE,
          PREPARATION_STEP_FAILED,
          PREPARATION_ASYNC_STEP_FAILED,
          DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION,
          DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE);

  public static boolean wasStampedeInfraFailure(DistributedExitCode exitCode) {
    return INFRA_ERROR_CODES.contains(exitCode);
  }

  /** Given a DistributedExitCode, returns closest equivalent ExitCode. */
  public static ExitCode convertToExitCode(DistributedExitCode distributedExitCode) {
    if (distributedExitCode == SUCCESSFUL) {
      return ExitCode.SUCCESS;
    } else if (wasStampedeInfraFailure(distributedExitCode)) {
      return ExitCode.STAMPEDE_INFRA_ERROR;
    }

    return ExitCode.BUILD_ERROR;
  }

  DistributedExitCode(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
