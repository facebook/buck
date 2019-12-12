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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;

/**
 * This Event is to be used to report {@link CacheCountersSummary} to listeners that register to the
 * event.
 */
public class CacheCountersSummaryEvent extends AbstractBuckEvent {
  private CacheCountersSummary summary;

  public static CacheCountersSummaryEvent newSummary(CacheCountersSummary summary) {
    return new CacheCountersSummaryEvent(EventKey.unique(), summary);
  }

  public CacheCountersSummary getSummary() {
    return this.summary;
  }

  private CacheCountersSummaryEvent(EventKey eventKey, CacheCountersSummary summary) {
    super(eventKey);
    this.summary = summary;
  }

  @Override
  protected String getValueString() {
    return "";
  }

  @Override
  public String getEventName() {
    return "CacheCountersSummary";
  }
}
