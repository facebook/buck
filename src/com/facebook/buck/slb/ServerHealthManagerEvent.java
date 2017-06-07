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

package com.facebook.buck.slb;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import org.immutables.value.Value;

public class ServerHealthManagerEvent extends AbstractBuckEvent {
  private final ServerHealthManagerEventData data;

  protected ServerHealthManagerEvent(ServerHealthManagerEventData data) {
    super(EventKey.unique());
    this.data = data;
  }

  public ServerHealthManagerEventData getData() {
    return data;
  }

  @Override
  protected String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractPerServerData {
    public abstract URI getServer();

    @Value.Default
    public boolean isServerUnhealthy() {
      return false;
    }

    @Value.Default
    public boolean isBestServer() {
      return false;
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractServerHealthManagerEventData {

    @Value.Default
    public boolean noHealthyServersAvailable() {
      return false;
    }

    public abstract ImmutableList<PerServerData> getPerServerData();
  }
}
