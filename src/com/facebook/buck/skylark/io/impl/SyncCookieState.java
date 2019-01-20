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

package com.facebook.buck.skylark.io.impl;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide state used to enable Watchman sync cookies only on the first query issued.
 *
 * <p>The first invocation should wait for Watchman to sync its state to make sure all local
 * modifications are picked up, but all successive invocations don't have to sync anymore, since the
 * state of the repo is not supposed to change during parsing and it's very expensive.
 */
public class SyncCookieState {
  private final AtomicBoolean useSyncCookies = new AtomicBoolean(true);

  /** Tell all users that */
  public void disableSyncCookies() {
    useSyncCookies.set(false);
  }

  public boolean shouldSyncCookies() {
    return useSyncCookies.get();
  }
}
