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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;

/** Remote execution events sent to the event bus. */
public class RemoteExecutionEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  private final RemoteExecutionInfo info;

  public RemoteExecutionEvent(RemoteExecutionInfo info) {
    super(EventKey.unique());
    this.info = info;
  }

  public RemoteExecutionInfo getInfo() {
    return info;
  }

  @Override
  protected String getValueString() {
    return String.format("%s:%s", info.getBuildTarget(), info.getState());
  }

  @Override
  public String getEventName() {
    return info.getState().toString();
  }
}
