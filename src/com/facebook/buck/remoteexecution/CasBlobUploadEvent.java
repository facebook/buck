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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.util.Scope;
import com.google.common.annotations.VisibleForTesting;

/** Started/Finished event pairs for CAS blob uploads . */
public abstract class CasBlobUploadEvent extends AbstractBuckEvent implements WorkAdvanceEvent {
  protected CasBlobUploadEvent(EventKey eventKey) {
    super(eventKey);
  }

  /** Send the Started and returns a Scoped object that sends the Finished event. */
  public static Scope sendEvent(final BuckEventBus eventBus, int blobCount, long sizeBytes) {
    final Started startedEvent = new Started(blobCount, sizeBytes);
    eventBus.post(startedEvent);
    return () -> eventBus.post(new Finished(startedEvent));
  }

  /** Upload to the CAS has started. */
  public static class Started extends CasBlobUploadEvent {
    private final int blobCount;
    private final long sizeBytes;

    @VisibleForTesting
    Started(int blobCount, long sizeBytes) {
      super(EventKey.unique());
      this.blobCount = blobCount;
      this.sizeBytes = sizeBytes;
    }

    @Override
    protected String getValueString() {
      return String.format("BlobCount=[%d] SizeBytes=[%d]", getBlobCount(), getSizeBytes());
    }

    public int getBlobCount() {
      return blobCount;
    }

    public long getSizeBytes() {
      return sizeBytes;
    }
  }

  /** Upload to the CAS has finished. */
  public static class Finished extends CasBlobUploadEvent {
    private final Started startedEvent;

    @VisibleForTesting
    Finished(Started startedEvent) {
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
