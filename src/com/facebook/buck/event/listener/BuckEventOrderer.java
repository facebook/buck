/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.event.BuckEvent;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;

/**
 * Orders {@link BuckEvent}s by the {@link BuckEvent#getNanoTime()} value. This is used to serialize
 * events coming in from multiple threads.
 */
public class BuckEventOrderer<T extends BuckEvent> implements AutoCloseable {

  private final long maximumSkewNanos;
  private final Function<T, Void> eventSinkFunction;
  private final Map<Long, Deque<T>> perThreadEventQueue;
  private final PriorityQueue<Deque<T>> oldestEventQueue;
  private long maximumNanoTime;

  /**
   * @param eventSinkFunction events for which we've determined ordering are dispatched here.
   * @param maximumSkew this is how many events
   */
  public BuckEventOrderer(
      Function<T, Void> eventSinkFunction,
      long maximumSkew,
      TimeUnit maximumSkewUnit) {
    this.maximumSkewNanos = maximumSkewUnit.toNanos(maximumSkew);
    this.eventSinkFunction = eventSinkFunction;
    this.perThreadEventQueue = new HashMap<>();
    this.oldestEventQueue = new PriorityQueue<>(1, new FirstEventTimestampOrdering());
    this.maximumNanoTime = 0L;
  }

  public void add(T buckEvent) {
    Deque<T> queue = getQueueForEvent(buckEvent);
    boolean shouldAddToEventQueue = queue.isEmpty();
    if (!queue.isEmpty() && queue.getLast().getNanoTime() > buckEvent.getNanoTime()) {
      // We assume inserting events configured at a time in the past is very rare.
      oldestEventQueue.remove(queue);
      List<T> mergedEventsList = new ArrayList<>(queue.size() + 1);
      while (!queue.isEmpty() && queue.getFirst().getNanoTime() <= buckEvent.getNanoTime()) {
        mergedEventsList.add(queue.removeFirst());
      }
      mergedEventsList.add(buckEvent);
      mergedEventsList.addAll(queue);
      queue.clear();
      queue.addAll(mergedEventsList);
      oldestEventQueue.add(queue);
    } else {
      queue.add(buckEvent);
    }
    if (shouldAddToEventQueue) {
      oldestEventQueue.add(queue);
    }
    if (maximumNanoTime < buckEvent.getNanoTime()) {
      maximumNanoTime = buckEvent.getNanoTime();
    }
    dispatchEventsOlderThan(maximumNanoTime - maximumSkewNanos);
  }

  private void dispatchEventsOlderThan(long upperTimeBound) {
    while (!oldestEventQueue.isEmpty() &&
        oldestEventQueue.peek().getFirst().getNanoTime() <= upperTimeBound) {
      Deque<T> queueWithOldestEvent = oldestEventQueue.remove();
      long upperTimeBoundForThisQueue = upperTimeBound;
      if (!oldestEventQueue.isEmpty()) {
        Deque<T> nextOldestQueue = oldestEventQueue.peek();
        Preconditions.checkState(
            queueWithOldestEvent.getFirst().getNanoTime() <=
                nextOldestQueue.getFirst().getNanoTime());
        if (nextOldestQueue.getFirst().getNanoTime() < upperTimeBoundForThisQueue) {
          upperTimeBoundForThisQueue = nextOldestQueue.getFirst().getNanoTime();
        }
      }
      while (!queueWithOldestEvent.isEmpty() &&
          queueWithOldestEvent.getFirst().getNanoTime() <= upperTimeBoundForThisQueue) {
        eventSinkFunction.apply(queueWithOldestEvent.removeFirst());
      }
      if (!queueWithOldestEvent.isEmpty()) {
        oldestEventQueue.add(queueWithOldestEvent);
      }
    }
  }

  @Override
  public void close() {
    dispatchEventsOlderThan(maximumNanoTime);
  }

  private Deque<T> getQueueForEvent(T buckEvent) {
    Deque<T> queue = perThreadEventQueue.get(buckEvent.getThreadId());
    if (queue == null) {
      queue = new ArrayDeque<>();
      perThreadEventQueue.put(buckEvent.getThreadId(), queue);
    }
    return queue;
  }

  private class FirstEventTimestampOrdering implements Comparator<Deque<T>> {
    @Override
    public int compare(Deque<T> o1, Deque<T> o2) {
      if (o1 == o2) {
        return 0;
      }
      T o1First = o1.getFirst();
      T o2First = o2.getFirst();
      int result = Long.compare(o1First.getNanoTime(), o2First.getNanoTime());
      if (result == 0) {
        result = Long.compare(o1First.getEventKey().getValue(), o2First.getEventKey().getValue());
      }
      Preconditions.checkState(result != 0);
      return result;
    }
  }

}
