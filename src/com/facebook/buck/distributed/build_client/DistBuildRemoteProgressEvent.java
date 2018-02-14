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

import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;

/** Client-side event to track remote build progress. */
public class DistBuildRemoteProgressEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  private final CoordinatorBuildProgress buildProgress;

  public DistBuildRemoteProgressEvent(CoordinatorBuildProgress buildProgress) {
    super(EventKey.unique());
    this.buildProgress = buildProgress;
  }

  public CoordinatorBuildProgress getBuildProgress() {
    return buildProgress;
  }

  @Override
  protected String getValueString() {
    return "";
  }

  @Override
  public String getEventName() {
    return DistBuildRemoteProgressEvent.class.getName();
  }
}
