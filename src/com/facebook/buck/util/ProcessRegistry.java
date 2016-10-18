/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Optional;

/**
 * A static helper class for process registration.
 */
public abstract class ProcessRegistry {

  /**
   * A callback to be called upon registering a process.
   */
  public interface ProcessRegisterCallback {
    void call(Object process, ProcessExecutorParams params);
  }

  /**
   * Sets the process register callback.
   */
  public static void setsProcessRegisterCallback(
      Optional<ProcessRegisterCallback> processRegisterCallback) {
    sProcessRegisterCallback = processRegisterCallback;
  }

  /**
   * Registers process for resource consumption tracking.
   */
  public static void registerProcess(
      Object process,
      ProcessExecutorParams params) {
    if (sProcessRegisterCallback.isPresent()) {
      sProcessRegisterCallback.get().call(process, params);
    }
  }

  private static Optional<ProcessRegisterCallback> sProcessRegisterCallback = Optional.absent();
}
