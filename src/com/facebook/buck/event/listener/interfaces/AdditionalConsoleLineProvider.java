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

package com.facebook.buck.event.listener.interfaces;

import com.google.common.collect.ImmutableList;

/** Provides additional output lines to the Console. */
public interface AdditionalConsoleLineProvider {
  /** Returns console lines representing the current state of Remote Execution. */
  ImmutableList<String> createConsoleLinesAtTime(long currentTimeMillis);
}
