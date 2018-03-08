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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;

/** Used to update the value attached to "local status" column in SuperConsole. */
public class StampedeLocalBuildStatusEvent extends AbstractBuckEvent implements LeafEvent {
  private final String status;
  private final String localBuildLinePrefix;

  public StampedeLocalBuildStatusEvent(String status) {
    this(status, "Local Steps");
  }

  public StampedeLocalBuildStatusEvent(String status, String localBuildLinePrefix) {
    super(EventKey.unique());
    this.status = status;
    this.localBuildLinePrefix = localBuildLinePrefix;
  }

  public String getStatus() {
    return status;
  }

  public String getLocalBuildLinePrefix() {
    return localBuildLinePrefix;
  }

  @Override
  protected String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  @Override
  public String getCategory() {
    return this.getClass().getName();
  }
}
