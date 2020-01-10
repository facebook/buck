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

package com.facebook.buck.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A singleton helper class for process registration. */
public class ProcessRegistry {

  private static final ProcessRegistry INSTANCE = new ProcessRegistry();

  /** Gets the singleton instance of this class. */
  public static ProcessRegistry getInstance() {
    return INSTANCE;
  }

  /** This is a helper singleton. */
  @VisibleForTesting
  ProcessRegistry() {}

  /** A callback to be called upon registering a process. */
  public interface ProcessRegisterCallback {
    void call(Object process, ProcessExecutorParams params, ImmutableMap<String, String> context);
  }

  /** Subscribes the process register callback. */
  public void subscribe(ProcessRegisterCallback processRegisterCallback) {
    processRegisterCallbacks.add(processRegisterCallback);
  }

  /** Unsubscribes the process register callback. */
  public void unsubscribe(ProcessRegisterCallback processRegisterCallback) {
    processRegisterCallbacks.remove(processRegisterCallback);
  }

  /** Registers the process and notifies the subscribers. */
  public void registerProcess(
      Object process, ProcessExecutorParams params, ImmutableMap<String, String> context) {
    for (ProcessRegisterCallback callback : processRegisterCallbacks) {
      callback.call(process, params, context);
    }
  }

  @VisibleForTesting
  Queue<ProcessRegisterCallback> processRegisterCallbacks = new ConcurrentLinkedQueue<>();
}
