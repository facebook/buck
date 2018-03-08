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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Collection;
import javax.annotation.concurrent.GuardedBy;

/** Fake implementation of {@link ScribeLogger} which records logged lines to memory. */
public final class FakeScribeLogger extends ScribeLogger {
  // Keep the lines in the order in which they were written.
  @GuardedBy("this")
  private final Multimap<String, String> loggedCategoryLines = LinkedListMultimap.create();

  @Override
  public ListenableFuture<Void> log(String category, Iterable<String> lines) {
    synchronized (this) {
      loggedCategoryLines.putAll(category, lines);
      return Futures.immediateFuture(null);
    }
  }

  public ImmutableList<String> getLinesForCategory(String category) {
    synchronized (this) {
      // This is guaranteed to be non-null even if no lines were logged to category.
      Collection<String> lines = loggedCategoryLines.get(category);

      // Make a defensive copy to ensure new lines aren't logged to the category after we return.
      return ImmutableList.copyOf(lines);
    }
  }

  @Override
  public void close() {}
}
