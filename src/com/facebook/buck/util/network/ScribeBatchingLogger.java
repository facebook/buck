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

package com.facebook.buck.util.network;

import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

/** Uploads log entries to the given scribe category. */
public class ScribeBatchingLogger extends AbstractBatchingLogger {

  private final ScribeLogger scribeLogger;
  private final String category;

  public ScribeBatchingLogger(String category, ScribeLogger scribeLogger) {
    this.category = category;
    this.scribeLogger = scribeLogger;
  }

  @Override
  protected ListenableFuture<Unit> logMultiple(ImmutableCollection<BatchEntry> data) {
    ImmutableList<String> lines =
        data.stream().map(BatchEntry::getLine).collect(ImmutableList.toImmutableList());
    return scribeLogger.log(category, lines);
  }
}
