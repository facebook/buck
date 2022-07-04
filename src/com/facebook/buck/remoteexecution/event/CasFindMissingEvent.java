/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.util.Scope;

/** Started/Finished event pairs for CAS findMissing requests. */
public abstract class CasFindMissingEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  protected CasFindMissingEvent(EventKey eventKey) {
    super(eventKey);
  }

  /** Send the Started and returns a Scoped object that sends the Finished event. */
  public static Scope sendEvent(final BuckEventBus eventBus, int blobCount) {
    final CasFindMissingEvent.Started startedEvent = new CasFindMissingEvent.Started(blobCount);
    eventBus.post(startedEvent);
    return () -> eventBus.post(new CasFindMissingEvent.Finished(startedEvent));
  }

  /** FindMissing call has started. */
  public static final class Started extends CasFindMissingEvent {
    private final int blobCount;

    public Started(int blobCount) {
      super(EventKey.unique());
      this.blobCount = blobCount;
    }

    public int getBlobCount() {
      return blobCount;
    }

    @Override
    protected String getValueString() {
      return String.format("blobCount=[%d]", getBlobCount());
    }
  }

  /** FindMissing call has finished. */
  public static class Finished extends CasFindMissingEvent {
    private final Started startedEvent;

    public Finished(Started startedEvent) {
      super(startedEvent.getEventKey());
      this.startedEvent = startedEvent;
    }

    public Started getStartedEvent() {
      return startedEvent;
    }

    @Override
    protected String getValueString() {
      return getStartedEvent().getValueString();
    }
  }

  @Override
  public String getEventName() {
    return getClass().getSimpleName();
  }
}
