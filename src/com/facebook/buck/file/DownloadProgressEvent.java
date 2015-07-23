/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.facebook.buck.event.AbstractBuckEvent;
import com.google.common.base.Preconditions;

import java.net.URI;

public class DownloadProgressEvent extends AbstractBuckEvent {

  private final URI uri;
  private final long downloadedSoFar;
  private final String size;

  public DownloadProgressEvent(URI uri, long size, long downloadedSoFar) {
    this.uri = uri;
    this.size = size == -1 ? "unknown" : String.valueOf(size);
    Preconditions.checkArgument(downloadedSoFar > 0);
    this.downloadedSoFar = downloadedSoFar;
  }

  @Override
  protected String getValueString() {
    return String.format("%s -> %d/%s", uri, downloadedSoFar, size);
  }

  @Override
  public String getEventName() {
    return "DownloadProgressEvent";
  }
}
