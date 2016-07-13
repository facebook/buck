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

package com.facebook.buck.util.network;

import com.facebook.buck.log.Logger;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class OfflineScribeLogger extends ScribeLogger {

  private static final Logger LOG = Logger.get(OfflineScribeLogger.class);

  private final ScribeLogger scribeLogger;
  private final ImmutableList<String> offlineCategories;

  public OfflineScribeLogger(ScribeLogger scribeLogger, ImmutableList<String> offlineCategories) {
    this.scribeLogger = scribeLogger;
    this.offlineCategories = offlineCategories;
  }

  @Override
  public ListenableFuture<Void> log(final String category, final Iterable<String> lines) {
    ListenableFuture<Void> upload = scribeLogger.log(category, lines);
    Futures.addCallback(
        upload,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result){
          }

          @Override
          public void onFailure(Throwable t) {
            if (offlineCategories.contains(category)) {
              LOG.debug("Storing Scribe lines from category: %s.", category);
              //TODO(michsien): Here should later go writing to mmaped file.
            }
          }
        });

    return upload;
  }

  @Override
  public void close() throws Exception {
    scribeLogger.close();
  }

}
