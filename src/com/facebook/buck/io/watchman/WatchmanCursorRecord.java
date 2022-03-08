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

package com.facebook.buck.io.watchman;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import java.util.Objects;

/** WatchmanCursorRecord saves the watchmanCursor and base commit from last build */
public class WatchmanCursorRecord {
  private String watchmanCursor;
  private String commitBase;

  public WatchmanCursorRecord(
      @JsonProperty("watchmanCursor") String watchmanCursor,
      @JsonProperty("commitBase") String commitBase) {
    this.watchmanCursor = watchmanCursor;
    this.commitBase = commitBase;
  }

  public String getWatchmanCursor() {
    return watchmanCursor;
  }

  public String getCommitBase() {
    return commitBase;
  }

  public void setWatchmanCursor(String watchmanCursor) {
    this.watchmanCursor = watchmanCursor;
  }

  public void setCommitBase(String commitBase) {
    this.commitBase = commitBase;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("WatchmanCursorRecord")
        .omitNullValues()
        .add("watchmanCursor", watchmanCursor)
        .add("commitBase", commitBase)
        .toString();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof WatchmanCursorRecord)) {
      return false;
    }
    WatchmanCursorRecord otherRecord = (WatchmanCursorRecord) other;
    return watchmanCursor.equals(otherRecord.watchmanCursor)
        && commitBase.equals(otherRecord.commitBase);
  }

  @Override
  public int hashCode() {
    return Objects.hash(watchmanCursor, commitBase);
  }
}
