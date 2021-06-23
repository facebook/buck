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

package com.facebook.buck.jvm.java;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;

/** Event use to emit information related to javacd */
public class JavaCDEvent extends AbstractBuckEvent {

  private final boolean enabled;

  public JavaCDEvent(boolean enabled) {
    super(EventKey.unique());
    this.enabled = enabled;
  }

  @Override
  public String getEventName() {
    return "JavaCDEvent";
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Override
  protected String getValueString() {
    return Boolean.toString(enabled);
  }
}
