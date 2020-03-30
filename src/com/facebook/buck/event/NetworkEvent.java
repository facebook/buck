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

package com.facebook.buck.event;

public abstract class NetworkEvent extends AbstractBuckEvent {
  protected NetworkEvent(EventKey eventKey) {
    super(eventKey);
  }

  public static class BytesReceivedEvent extends NetworkEvent {
    private final long bytesReceived;

    public BytesReceivedEvent(long bytesReceived) {
      super(EventKey.unique());
      this.bytesReceived = bytesReceived;
    }

    @Override
    protected String getValueString() {
      return "";
    }

    @Override
    public String getEventName() {
      return "BytesReceivedEvent";
    }

    public long getBytesReceived() {
      return bytesReceived;
    }
  }
}
