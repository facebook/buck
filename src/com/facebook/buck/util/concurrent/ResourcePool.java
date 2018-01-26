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

package com.facebook.buck.util.concurrent;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Allows multiple concurrently executing futures to share a constrained number of resources.
 *
 * <p>Resources are lazily created up till a fixed maximum. If more than max resources are requested
 * the associated 'requests' are queued up in the resourceRequests field. As soon as a resource is
 * returned it will be used to satisfy the first pending request, otherwise it is stored in the
 * parkedResources queue.
 *
 * <p>If the resourceSupplier throws a RuntimeException the Future associated with the failed
 * attempt to create the resource will contain the relevant exception. Any subsequent requests the
 * pool will get will attempt to create a new resource.
 *
 * <p>If the {@link ResourceUsageErrorPolicy#RECYCLE} error usage policy is specified then, in case
 * of errors when "using" a resource it is assumed to be defective, will be retired and a new
 * resource will be requested from the supplier. The Future associated with the failed attempt to
 * use the resource will contain the relevant exception.
 */
public class ResourcePool<R extends AutoCloseable> implements AutoCloseable {
  private static final Logger LOG = Logger.get(ResourcePool.class);

  private final int maxResources;
  private final ResourceUsageErrorPolicy resourceUsageErrorPolicy;

  @GuardedBy("this")
  private final Supplier<R> resourceSupplier;

  @GuardedBy("this")
  private final List<R> createdResources;

  @GuardedBy("this")
  private final Deque<R> parkedResources;

  @GuardedBy("this")
  private final Deque<SettableFuture<Void>> resourceRequests;

  private final AtomicBoolean closing;

  @GuardedBy("this")
  private @Nullable ListenableFuture<Void> shutdownFuture;

  @GuardedBy("this")
  private final Set<ListenableFuture<?>> pendingWork;

  /**
   * @param maxResources maximum number of resources to use concurrently.
   * @param resourceSupplier function used to create a new resource. It should never block, it may
   *     be called more than maxResources times if processing resources throws exceptions.
   */
  public ResourcePool(
      int maxResources,
      ResourceUsageErrorPolicy resourceUsageErrorPolicy,
      Supplier<R> resourceSupplier) {
    Preconditions.checkArgument(maxResources > 0);

    this.maxResources = maxResources;
    this.resourceUsageErrorPolicy = resourceUsageErrorPolicy;
    this.resourceSupplier = resourceSupplier;
    this.createdResources = new ArrayList<>();
    this.parkedResources = new ArrayDeque<>();
    this.resourceRequests = new ArrayDeque<>();
    this.closing = new AtomicBoolean(false);
    this.shutdownFuture = null;
    this.pendingWork = new HashSet<>();
  }

  public synchronized void callOnEachResource(Consumer<R> withResource) {
    Preconditions.checkState(!closing.get());
    for (R resource : createdResources) {
      withResource.accept(resource);
    }
  }

  /**
   * @param executorService where to perform the resource processing. Should really be a "real"
   *     executor (not a directExecutor).
   * @return a {@link ListenableFuture} containing the result of the processing. The future will be
   *     cancelled if the {@link ResourcePool#close()} method is called.
   */
  public synchronized <T> ListenableFuture<T> scheduleOperationWithResource(
      ThrowingFunction<R, T> withResource, final ListeningExecutorService executorService) {
    Preconditions.checkState(!closing.get());

    final ListenableFuture<T> futureWork =
        Futures.transformAsync(
            initialSchedule(),
            new AsyncFunction<Void, T>() {
              @Override
              public ListenableFuture<T> apply(Void input) throws Exception {
                Either<R, ListenableFuture<Void>> resourceRequest = requestResource();
                if (resourceRequest.isLeft()) {
                  R resource = resourceRequest.getLeft();
                  boolean resourceIsDefunct = false;
                  try {
                    return Futures.immediateFuture(withResource.apply(resource));
                  } catch (Exception e) {
                    resourceIsDefunct =
                        (resourceUsageErrorPolicy == ResourceUsageErrorPolicy.RETIRE);
                    throw e;
                  } finally {
                    returnResource(resource, resourceIsDefunct);
                  }
                } else {
                  return Futures.transformAsync(resourceRequest.getRight(), this, executorService);
                }
              }
            },
            executorService);

    pendingWork.add(futureWork);
    futureWork.addListener(
        () -> {
          synchronized (ResourcePool.this) {
            pendingWork.remove(futureWork);
          }
        },
        executorService);

    // If someone else calls cancel on `futureWork` it makes it impossible to wait for that future
    // to finish using the resource, which then makes shutdown code exit too early.
    return Futures.nonCancellationPropagating(futureWork);
  }

  private synchronized ListenableFuture<Void> initialSchedule() {
    // If we'll (potentially) be allowed to create a resource or there are some parked then we'll
    // take the chance and attempt to run immediately.
    if (allowedToCreateResource() || !parkedResources.isEmpty()) {
      return Futures.immediateFuture(null);
    }
    // All possible resources are currently occupied. Because we're in a synchronized block, even
    // if one becomes available immediately after this call returns it will simply make this future
    // runnable, so we'll be able to progress.
    return scheduleNewResourceRequest();
  }

  private synchronized Either<R, ListenableFuture<Void>> requestResource() {
    Optional<R> resource = obtainResource();
    if (resource.isPresent()) {
      return Either.ofLeft(resource.get());
    }
    return Either.ofRight(scheduleNewResourceRequest());
  }

  private synchronized ListenableFuture<Void> scheduleNewResourceRequest() {
    if (closing.get()) {
      return Futures.immediateCancelledFuture();
    }
    SettableFuture<Void> resourceFuture = SettableFuture.create();
    resourceRequests.add(resourceFuture);
    return resourceFuture;
  }

  private synchronized Optional<R> obtainResource() {
    if (closing.get()) {
      return Optional.empty();
    }
    R resource = parkedResources.pollFirst();
    if (resource != null) {
      return Optional.of(resource);
    }
    return createIfAllowed();
  }

  private synchronized void returnResource(R resource, boolean resourceIsDefunct) {
    if (resourceIsDefunct) {
      createdResources.remove(resource);
      try {
        resource.close();
      } catch (Exception e) {
        LOG.info(e, "Error shutting down a defunct resource.");
      }
    } else {
      parkedResources.add(resource);
    }
    scheduleNextRequest();
  }

  private synchronized void scheduleNextRequest() {
    while (true) {
      SettableFuture<Void> nextRequest = resourceRequests.pollFirst();
      // Queue empty.
      if (nextRequest == null) {
        return;
      }
      // A false return value means the future was failed/cancelled, so we ignore it.
      if (nextRequest.set(null)) {
        return;
      }
    }
  }

  private synchronized boolean allowedToCreateResource() {
    return !closing.get() && (createdResources.size() < maxResources);
  }

  private synchronized Optional<R> createIfAllowed() {
    if (!allowedToCreateResource()) {
      return Optional.empty();
    }
    R resource = Preconditions.checkNotNull(resourceSupplier.get());
    createdResources.add(resource);
    return Optional.of(resource);
  }

  @Nullable
  public synchronized ListenableFuture<Void> getShutdownFullyCompleteFuture() {
    Preconditions.checkState(
        closing.get(), "This method should not be called before the .close() method is called.");
    return Preconditions.checkNotNull(shutdownFuture);
  }

  @Override
  public synchronized void close() {
    Preconditions.checkState(!closing.get());
    closing.set(true);

    // Unblock all waiting requests.
    for (SettableFuture<Void> request : resourceRequests) {
      request.set(null);
    }
    resourceRequests.clear();

    // Any processing that is currently taking place will be allowed to complete (as it won't notice
    // `closing` is true.
    // Any scheduled (but not executing) resource requests should notice `closing` is true and
    // mark themselves as cancelled.
    // Therefore `closeFuture` should allow us to wait for any resources that are in use.
    ListenableFuture<List<Object>> closeFuture = Futures.successfulAsList(pendingWork);

    // As silly as it seems this is the only reliable way to make sure we run the shutdown code.
    // Reusing an external executor means we run the risk of it being shut down before the cleanup
    // future is ready to run (which causes it to never run).
    // Using a direct executor means we run the chance of executing shutdown synchronously (which
    // we try to avoid).
    final ExecutorService executorService =
        MostExecutors.newSingleThreadExecutor("resource shutdown");

    // It is possible that more requests for work are scheduled at this point, however they should
    // all early-out due to `closing` being set to true, so we don't really care about those.
    shutdownFuture =
        Futures.transformAsync(
            closeFuture,
            new AsyncFunction<List<Object>, Void>() {
              @Override
              public ListenableFuture<Void> apply(List<Object> input) throws Exception {
                synchronized (ResourcePool.this) {
                  if (parkedResources.size() != createdResources.size()) {
                    LOG.error("Whoops! Some resource are still in use during shutdown.");
                  }
                  // Now that pending work is done we can close all resources.
                  for (R resource : createdResources) {
                    resource.close();
                  }
                  if (!resourceRequests.isEmpty()) {
                    LOG.error(
                        "Error shutting down ResourcePool: "
                            + "there should be no enqueued resource requests.");
                  }
                }
                executorService.shutdown();
                return Futures.immediateFuture(null);
              }
            },
            executorService);
  }

  /** Describes how to handle errors that take place during resource usage. */
  public enum ResourceUsageErrorPolicy {
    RETIRE,
    RECYCLE
  }

  public interface ThrowingFunction<T, R> {
    R apply(T t) throws Exception;
  }
}
