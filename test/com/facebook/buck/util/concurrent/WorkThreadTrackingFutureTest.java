/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.util.concurrent;

import com.facebook.buck.util.Scope;
import com.google.common.base.Throwables;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

public class WorkThreadTrackingFutureTest {
  @Test(expected = WorkThreadTrackingFuture.RecursiveGetException.class)
  public void throwsExceptionIfWhenCalledByWorkThread() throws Exception {
    AtomicReference<WorkThreadTrackingFuture<Void>> thisFuture = new AtomicReference<>();

    CompletableFuture<Void> trigger = new CompletableFuture<>();
    thisFuture.set(
        WorkThreadTrackingFuture.create(
            workTracker ->
                trigger.thenApply(
                    ignored -> {
                      try (Scope scope = workTracker.start()) {
                        return thisFuture.get().get();
                      } catch (InterruptedException | ExecutionException e) {
                        throw new IllegalStateException("Unexpected checked exception.", e);
                      }
                    })));

    trigger.complete(null);
    try {
      thisFuture.get().get();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), Exception.class);
      throw e;
    }
  }

  @Test(expected = WorkThreadTrackingFuture.RecursiveGetException.class)
  public void throwsExceptionWhenTimedGetCalledByWorkThread() throws Exception {
    AtomicReference<WorkThreadTrackingFuture<Void>> thisFuture = new AtomicReference<>();

    CompletableFuture<Void> trigger = new CompletableFuture<>();
    thisFuture.set(
        WorkThreadTrackingFuture.create(
            workTracker ->
                trigger.thenApply(
                    ignored -> {
                      try (Scope scope = workTracker.start()) {
                        return thisFuture.get().get(1, TimeUnit.NANOSECONDS);
                      } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        throw new IllegalStateException("Unexpected checked exception.", e);
                      }
                    })));

    trigger.complete(null);
    try {
      thisFuture.get().get();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), Exception.class);
      throw e;
    }
  }

  @Test
  public void handlesQueryForCurrentWorkThread() throws Exception {
    AtomicReference<WorkThreadTrackingFuture<Boolean>> thisFuture = new AtomicReference<>();

    CompletableFuture<Void> trigger = new CompletableFuture<>();
    thisFuture.set(
        WorkThreadTrackingFuture.create(
            workTracker ->
                trigger.thenApply(
                    ignored -> {
                      try (Scope scope = workTracker.start()) {
                        return thisFuture.get().isBeingWorkedOnByCurrentThread();
                      }
                    })));

    trigger.complete(null);
    Assert.assertTrue(thisFuture.get().get());
  }

  @Test
  public void completedFutureActsLikeCompletedFuture() throws Exception {
    Object object = new Object();
    WorkThreadTrackingFuture<Object> completed = WorkThreadTrackingFuture.completedFuture(object);
    Assert.assertTrue("Should be done.", completed.isDone());
    Assert.assertSame("Passed in object should be returned.", object, completed.get());
  }
}
