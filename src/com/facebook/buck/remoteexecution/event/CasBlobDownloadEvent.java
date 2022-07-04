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

/** Started/Finished event pairs for CAS blob downloads . */
public abstract class CasBlobDownloadEvent extends AbstractBuckEvent implements WorkAdvanceEvent {
  protected CasBlobDownloadEvent(EventKey eventKey) {
    super(eventKey);
  }

  @Override
  public String getEventName() {
    return getClass().getSimpleName();
  }

  /** Send the Started and returns a Scoped object that sends the Finished event. */
  public static Scope sendEvent(final BuckEventBus eventBus, CasBlobBatchInfo batchInfo) {
    final Started startedEvent = new Started(batchInfo);
    eventBus.post(startedEvent);
    return () -> eventBus.post(new Finished(startedEvent));
  }

  /** Download to the CAS has started. */
  public static final class Started extends CasBlobDownloadEvent {
    private final CasBlobBatchInfo batchInfo;

    public Started(CasBlobBatchInfo batchInfo) {
      super(EventKey.unique());
      this.batchInfo = batchInfo;
    }

    public int getBlobCount() {
      return batchInfo.getBlobCount();
    }

    public int getSmallBlobCount() {
      return batchInfo.getSmallBlobCount();
    }

    public int getLargeBlobCount() {
      return batchInfo.getLargeBlobCount();
    }

    public long getSizeBytes() {
      return batchInfo.getBlobSize();
    }

    public long getSmallSizeBytes() {
      return batchInfo.getSmallBlobSize();
    }

    public long getLargeSizeBytes() {
      return batchInfo.getLargeBlobSize();
    }

    @Override
    protected String getValueString() {
      return String.format("blobCount=[%d] sizeBytes=[%d]", getBlobCount(), getSizeBytes());
    }
  }

  /** Download to the CAS has finished. */
  public static class Finished extends CasBlobDownloadEvent {
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
}
