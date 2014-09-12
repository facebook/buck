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
import com.facebook.buck.event.BuckEvent;
import com.google.common.base.Preconditions;

import java.net.URI;

public abstract class DownloadEvent extends AbstractBuckEvent {

  protected URI uri;

  private DownloadEvent(URI uri) {
    this.uri = Preconditions.checkNotNull(uri);
  }

  @Override
  protected String getValueString() {
    return uri.toString();
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof DownloadEvent)) {
      return false;
    }
    return uri.equals(((DownloadEvent) event).uri);
  }

  public static Started started(URI uri) {
    return new Started(uri);
  }

  public static Finished finished(URI uri) {
    return new Finished(uri);
  }

  public static class Started extends DownloadEvent {
    public Started(URI uri) {
      super(uri);
    }

    @Override
    public String getEventName() {
      return "DownloadStarted";
    }
  }

  public static class Finished extends DownloadEvent {
    public Finished(URI uri) {
      super(uri);
    }

    @Override
    public String getEventName() {
      return "DownloadFinished";
    }
  }
}
