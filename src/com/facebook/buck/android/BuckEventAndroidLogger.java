/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.android.common.annotations.NonNull;
import com.android.common.utils.ILogger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.event.ThrowableLogEvent;
import com.google.common.base.Strings;

/**
 * Implementation of {@link ILogger} which posts to an {@link BuckEventBus}
 */
public class BuckEventAndroidLogger implements ILogger {

  private final BuckEventBus eventBus;

  public BuckEventAndroidLogger(BuckEventBus eventBus) {
    this.eventBus = eventBus;
  }

  @Override
  public void error(Throwable throwable, String errorFormat, Object... args) {
    eventBus.post(ThrowableLogEvent.create(throwable, Strings.nullToEmpty(errorFormat), args));
  }

  @Override
  public void warning(@NonNull String msgFormat, Object... args) {
    eventBus.post(LogEvent.warning(msgFormat, args));
  }

  @Override
  public void info(@NonNull String msgFormat, Object... args) {
    eventBus.post(LogEvent.info(msgFormat, args));
  }

  @Override
  public void verbose(@NonNull String msgFormat, Object... args) {
    eventBus.post(LogEvent.info(msgFormat, args));
  }
}
