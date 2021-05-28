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

package com.facebook.buck.event;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * An implementation of {@link BuckEvent}s used for gathering performance statistics. These are only
 * intended to be used with the trace viewer and should not be used to communicate information
 * between parts of the system.
 */
public abstract class SimplePerfEvent extends AbstractBuckEvent implements LeafEvent {

  public SimplePerfEvent(EventKey eventKey) {
    super(eventKey);
  }

  public enum Type {
    STARTED("Started"),
    UPDATED("Updated"),
    FINISHED("Finished");

    private final String value;

    public String getValue() {
      return value;
    }

    Type(String value) {
      this.value = value;
    }
  }

  /**
   * @return the title of the event. Different from event_id in Downward API's ChromeTraceEvent. The
   *     JSON representation of this field is "eventId" because this is the public API exposed via
   *     the websocket to the IntelliJ plugin and anyone else who might be listening.
   */
  @JsonProperty("eventId")
  public abstract PerfEventTitle getTitle();

  /** @return event type. */
  public abstract Type getEventType();

  /** @return information associated with the event. */
  public abstract ImmutableMap<String, Object> getEventInfo();

  /** @return event's category. */
  @Override
  public abstract String getCategory();

  public boolean isLogToChromeTrace() {
    return true;
  }

  @Override
  public boolean supportsAggregation() {
    return false;
  }

  /**
   * Prefer using {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)} when possible.
   *
   * <p>Create an event that indicates the start of a particular operation.
   *
   * @param perfEventTitle identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param info Any additional information to be saved with the event. This will be serialized and
   *     potentially sent over the wire, so please keep this small.
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(PerfEventTitle perfEventTitle, ImmutableMap<String, Object> info) {
    return new StartedImpl(perfEventTitle, info);
  }

  /**
   * Convenience wrapper around {@link SimplePerfEvent#started(PerfEventTitle, ImmutableMap)}.
   *
   * @param perfEventTitle identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(PerfEventTitle perfEventTitle) {
    return started(perfEventTitle, ImmutableMap.of());
  }

  /**
   * Convenience wrapper around {@link SimplePerfEvent#started(PerfEventTitle, ImmutableMap)}.
   *
   * @param perfEventTitle identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param k1 name of the value to be stored with the event.
   * @param v1 value to be stored. This will be serialized and potentially sent over the wire, so
   *     please keep this small.
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(PerfEventTitle perfEventTitle, String k1, Object v1) {
    return started(perfEventTitle, ImmutableMap.of(k1, v1));
  }

  /**
   * Convenience wrapper around {@link SimplePerfEvent#started(PerfEventTitle, ImmutableMap)}.
   *
   * @param perfEventTitle identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param k1 name of the value to be stored with the event.
   * @param v1 value to be stored. This will be serialized and potentially sent over the wire, so
   *     please keep this small.
   * @param k2 name of the value to be stored with the event.
   * @param v2 value to be stored. This will be serialized and potentially sent over the wire, so
   *     please keep this small.
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(
      PerfEventTitle perfEventTitle, String k1, Object v1, String k2, Object v2) {
    return started(perfEventTitle, ImmutableMap.of(k1, v1, k2, v2));
  }

  public static Started started(
      PerfEventTitle perfEventTitle, String category, ImmutableMap<String, Object> info) {
    return new StartedImpl(perfEventTitle, category, info);
  }

  /**
   * Creates a scope within which the measured operation takes place.
   *
   * <pre>
   * try (perfEvent = SimplePerfEvent.scope(bus, PerfEventTitle.of("BurnCpu"))) {
   *   int bitsFlopped = 0;
   *   for (int i = 0; i < 1000; i++) {
   *     bitsFlopped += invokeExpensiveOp();
   *     if (bitsFlopped % 100 == 0) {
   *       // Some of these events are of special interest.
   *       perfEvent.update("noFlopIteration", i);
   *     }
   *   }
   *   perfEvent.appendFinishedInfo("totalBitsFlopped", bitsFlopped);
   * }
   * </pre>
   *
   * @param bus the {@link IsolatedEventBus} to post update and finished events to.
   * @param perfEventTitle identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param info Any additional information to be saved with the event. This will be serialized and
   *     potentially sent over the wire, so please keep this small.
   * @return an AutoCloseable which will send the finished event as soon as control flow exits the
   *     scope.
   */
  public static Scope scopeWithActionId(
      IsolatedEventBus bus,
      @Nullable ActionId actionId,
      PerfEventTitle perfEventTitle,
      ImmutableMap<String, Object> info) {
    StartedImpl started = new StartedImpl(perfEventTitle, info);
    bus.post(started, actionId);
    return new SimplePerfEventScope(bus, actionId, started);
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scope(IsolatedEventBus bus, PerfEventTitle perfEventTitle) {
    return scopeWithActionId(bus, null, perfEventTitle, ImmutableMap.of());
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scope(
      IsolatedEventBus bus, PerfEventTitle perfEventTitle, String k1, Object v1) {
    return scopeWithActionId(bus, null, perfEventTitle, ImmutableMap.of(k1, v1));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scope(
      IsolatedEventBus bus,
      PerfEventTitle perfEventTitle,
      String k1,
      Object v1,
      String k2,
      Object v2) {
    return scopeWithActionId(bus, null, perfEventTitle, ImmutableMap.of(k1, v1, k2, v2));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(IsolatedEventBus, PerfEventTitle,
   * ImmutableMap)}.
   */
  public static Scope scope(
      IsolatedEventBus bus, PerfEventTitle perfEventTitle, ImmutableMap<String, Object> info) {
    return scopeWithActionId(bus, null, perfEventTitle, info);
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scope(Optional<IsolatedEventBus> bus, PerfEventTitle perfEventTitle) {
    return bus.map(
            IsolatedEventBus ->
                scopeWithActionId(IsolatedEventBus, null, perfEventTitle, ImmutableMap.of()))
        .orElseGet(NoopScope::new);
  }

  /** Convenience wrapper for {@link #scope(IsolatedEventBus, PerfEventTitle)}. */
  public static Scope scope(Optional<IsolatedEventBus> bus, String perfEventName) {
    return scope(bus, PerfEventTitle.of(perfEventName));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scope(IsolatedEventBus bus, String perfEventName) {
    return scopeWithActionId(bus, null, PerfEventTitle.of(perfEventName), ImmutableMap.of());
  }

  public static Scope scopeWithActionId(
      IsolatedEventBus bus, ActionId actionId, String perfEventName) {
    return scopeWithActionId(bus, actionId, PerfEventTitle.of(perfEventName), ImmutableMap.of());
  }

  /**
   * Like {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String, PerfEventTitle,
   * ImmutableMap)}, but doesn't post the events if the duration of the scope is below a certain
   * threshold. NOTE: The events are buffered before being posted. The longer they are buffered, the
   * more likely any logging code will be confused. Ideally the threshold should not exceed 100ms.
   *
   * @param bus the {@link IsolatedEventBus} to post update and finished events to.
   * @param perfEventTitle identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param info Any additional information to be saved with the event. This will be serialized and
   *     potentially sent over the wire, so please keep this small.
   * @param minimumTime the scope must take at least this long for the event to be posted.
   * @param timeUnit time unit for minimumTime.
   * @return an AutoCloseable which will send the finished event as soon as control flow exits the
   *     scope.
   */
  public static Scope scopeIgnoringShortEvents(
      IsolatedEventBus bus,
      @Nullable ActionId actionId,
      PerfEventTitle perfEventTitle,
      ImmutableMap<String, Object> info,
      Scope parentScope,
      long minimumTime,
      TimeUnit timeUnit) {
    if (minimumTime == 0) {
      return scopeWithActionId(bus, actionId, perfEventTitle, info);
    }
    StartedImpl started = new StartedImpl(perfEventTitle, info);
    bus.timestamp(started);
    return new MinimumTimePerfEventScope(
        bus, actionId, started, parentScope, timeUnit.toNanos(minimumTime));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scopeIgnoringShortEvents(
      IsolatedEventBus bus,
      PerfEventTitle perfEventTitle,
      Scope parentScope,
      long minimumTime,
      TimeUnit timeUnit) {
    return scopeIgnoringShortEvents(
        bus, null, perfEventTitle, ImmutableMap.of(), parentScope, minimumTime, timeUnit);
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scopeWithActionId (IsolatedEventBus, String,
   * PerfEventTitle, ImmutableMap)}.
   */
  public static Scope scopeIgnoringShortEvents(
      IsolatedEventBus bus,
      PerfEventTitle perfEventTitle,
      String k1,
      Object v1,
      Scope parentScope,
      long minimumTime,
      TimeUnit timeUnit) {
    return scopeIgnoringShortEvents(
        bus, null, perfEventTitle, ImmutableMap.of(k1, v1), parentScope, minimumTime, timeUnit);
  }

  /** Represents the scope within which a particular performance operation is taking place. */
  public interface Scope extends AutoCloseable {

    /**
     * Creates and sends an event which indicates an update in state of the scope. Every invocation
     * creates and sends a new event.
     *
     * @param info Any additional information to be saved with the event. This will be serialized
     *     and potentially sent over the wire, so please keep this small.
     */
    void update(ImmutableMap<String, Object> info);

    /** Convenience wrapper for {@link Scope#update(ImmutableMap)}. */
    void update(String k1, Object v1);

    /** Convenience wrapper for {@link Scope#update(ImmutableMap)}. */
    void update(String k1, Object v1, String k2, Object v2);

    /**
     * Appends information to the finished event which will be sent when control exits the scope.
     *
     * @param k1 name of the value to be stored with the event.
     * @param v1 value to be stored. This will be serialized and potentially sent over the wire, so
     *     please keep this small.
     */
    void appendFinishedInfo(String k1, Object v1);

    /** Increments a counter that will be appended to the finished event. */
    void incrementFinishedCounter(String key, long increment);

    @Override
    void close();
  }

  /** The beginning of a {@link SimplePerfEvent}. */
  public abstract static class Started extends AbstractChainablePerfEvent {

    public Started(
        EventKey eventKey,
        PerfEventTitle perfEventTitle,
        Type perfEventType,
        ImmutableMap<String, Object> info) {
      super(eventKey, perfEventTitle, perfEventType, info);
    }

    public Started(
        EventKey eventKey,
        PerfEventTitle perfEventTitle,
        Type perfEventType,
        String category,
        ImmutableMap<String, Object> info) {
      super(eventKey, perfEventTitle, perfEventType, category, info);
    }

    /**
     * Creates a new event which indicates an update to the performance data being gathered.
     *
     * @param info Any additional information to be saved with the event. This will be serialized
     *     and potentially sent over the wire, so please keep this small.
     * @return An event which should be posted to the {@link IsolatedEventBus}.
     */
    public abstract SimplePerfEvent createUpdateEvent(ImmutableMap<String, Object> info);

    /** Convenience wrapper for {@link Started#createUpdateEvent(String, Object)}. */
    public abstract SimplePerfEvent createUpdateEvent(String k1, Object v1);

    /** Convenience wrapper for {@link Started#createUpdateEvent(String, Object)}. */
    public abstract SimplePerfEvent createUpdateEvent(String k1, Object v1, String k2, Object v2);

    /**
     * Creates a new event which indicates the end of a performance event.
     *
     * @param info Any additional information to be saved with the event. This will be serialized
     *     and potentially sent over the wire, so please keep this small.
     * @return An event which should be posted to the {@link IsolatedEventBus}.
     */
    public abstract SimplePerfEvent createFinishedEvent(ImmutableMap<String, Object> info);

    /** Convenience wrapper for {@link Started#createFinishedEvent(ImmutableMap)}. */
    public abstract SimplePerfEvent createFinishedEvent();

    /** Convenience wrapper for {@link Started#createFinishedEvent(ImmutableMap)}. */
    public abstract SimplePerfEvent createFinishedEvent(String k1, Object v1);

    /** Convenience wrapper for {@link Started#createFinishedEvent(ImmutableMap)}. */
    public abstract SimplePerfEvent createFinishedEvent(String k1, Object v1, String k2, Object v2);
  }

  /**
   * This is an identifier for the various performance event names in use in the system. Should be
   * CamelCase (first letter capitalized). Matches "title" in the chrome trace.
   */
  @BuckStyleValue
  public abstract static class PerfEventTitle {

    @JsonValue
    public abstract String getValue();

    @Value.Check
    protected void nameIsNotEmpty() {
      Preconditions.checkArgument(!getValue().isEmpty());
    }

    public static PerfEventTitle of(String value) {
      return ImmutablePerfEventTitle.ofImpl(value);
    }
  }

  private static class NoopScope implements AutoCloseable, Scope {

    @Override
    public void update(ImmutableMap<String, Object> info) {}

    @Override
    public void update(String k1, Object v1) {}

    @Override
    public void update(String k1, Object v1, String k2, Object v2) {}

    @Override
    public void appendFinishedInfo(String k1, Object v1) {}

    @Override
    public void incrementFinishedCounter(String key, long increment) {}

    @Override
    public void close() {}
  }

  private static class SimplePerfEventScope implements AutoCloseable, Scope {

    protected final IsolatedEventBus bus;
    protected final @Nullable ActionId actionId;
    protected final StartedImpl started;
    protected final ConcurrentMap<String, AtomicLong> finishedCounters;
    protected final ImmutableMap.Builder<String, Object> finishedInfoBuilder;

    public SimplePerfEventScope(
        IsolatedEventBus bus, @Nullable ActionId actionId, StartedImpl started) {
      this.bus = bus;
      this.actionId = actionId;
      this.started = started;
      this.finishedCounters = new ConcurrentHashMap<>();
      this.finishedInfoBuilder = ImmutableMap.builder();
    }

    @Override
    public void update(ImmutableMap<String, Object> info) {
      bus.post(new Updated(started, info), actionId);
    }

    @Override
    public void update(String k1, Object v1) {
      update(ImmutableMap.of(k1, v1));
    }

    @Override
    public void update(String k1, Object v1, String k2, Object v2) {
      update(ImmutableMap.of(k1, v1, k2, v2));
    }

    @Override
    public void appendFinishedInfo(String key, Object value) {
      finishedInfoBuilder.put(key, value);
    }

    @Override
    public void incrementFinishedCounter(String key, long delta) {
      AtomicLong count = finishedCounters.get(key);
      if (count == null) {
        finishedCounters.putIfAbsent(key, new AtomicLong(0));
        count = finishedCounters.get(key);
      }
      count.addAndGet(delta);
    }

    protected Finished createFinishedEvent() {
      finishedInfoBuilder.putAll(Maps.transformValues(finishedCounters, AtomicLong::get));
      return new Finished(started, finishedInfoBuilder.build());
    }

    @Override
    public void close() {
      bus.post(createFinishedEvent(), actionId);
    }
  }

  private static class MinimumTimePerfEventScope extends SimplePerfEventScope {

    private final long minimumDurationNanos;
    private final Scope parentScope;
    private final List<AbstractChainablePerfEvent> events;

    public MinimumTimePerfEventScope(
        IsolatedEventBus bus,
        @Nullable ActionId actionId,
        StartedImpl started,
        Scope parentScope,
        long minimumDurationNanos) {
      super(bus, actionId, started);
      this.minimumDurationNanos = minimumDurationNanos;
      this.parentScope = parentScope;
      this.events = new ArrayList<>();
      this.events.add(started);
    }

    @Override
    public void update(ImmutableMap<String, Object> info) {
      Updated event = new Updated(started, info);
      bus.timestamp(event);
      events.add(event);
    }

    @Override
    public void close() {
      Finished finished = createFinishedEvent();
      bus.timestamp(finished);
      events.add(finished);

      long delta = finished.getNanoTime() - started.getNanoTime();
      if (delta >= minimumDurationNanos) {
        for (AbstractChainablePerfEvent event : events) {
          bus.postWithoutConfiguring(event, super.actionId);
        }
      } else {
        parentScope.incrementFinishedCounter(
            started.getTitle().getValue() + "_accumulated_duration_ns", delta);
        parentScope.incrementFinishedCounter(
            started.getTitle().getValue() + "_accumulated_count", 1);
      }
    }
  }

  /** Common implementation for the Started/Updated/Finished events. */
  private abstract static class AbstractChainablePerfEvent extends SimplePerfEvent {

    private final PerfEventTitle perfEventTitle;
    private final Type perfEventType;
    private final ImmutableMap<String, Object> info;
    private final String category;

    public AbstractChainablePerfEvent(
        EventKey eventKey,
        PerfEventTitle perfEventTitle,
        Type perfEventType,
        ImmutableMap<String, Object> info) {
      this(eventKey, perfEventTitle, perfEventType, "buck", info);
    }

    public AbstractChainablePerfEvent(
        EventKey eventKey,
        PerfEventTitle perfEventTitle,
        Type perfEventType,
        String category,
        ImmutableMap<String, Object> info) {
      super(eventKey);
      this.perfEventTitle =
          PerfEventTitle.of(
              CaseFormat.UPPER_CAMEL
                  .converterTo(CaseFormat.LOWER_UNDERSCORE)
                  .convert(perfEventTitle.getValue()));
      this.perfEventType = perfEventType;
      this.category = category;
      this.info = info;
    }

    @Override
    public PerfEventTitle getTitle() {
      return perfEventTitle;
    }

    @Override
    public Type getEventType() {
      return perfEventType;
    }

    @Override
    public String getCategory() {
      return category;
    }

    @Override
    public ImmutableMap<String, Object> getEventInfo() {
      return info;
    }

    @Override
    protected String getValueString() {
      return Joiner.on(',').withKeyValueSeparator(":").join(info);
    }

    @Override
    public String getEventName() {
      return "PerfEvent" + '.' + perfEventTitle.getValue() + '.' + perfEventType.getValue();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), perfEventTitle, perfEventType, info);
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      AbstractChainablePerfEvent other = (AbstractChainablePerfEvent) o;
      return other.perfEventTitle.equals(perfEventTitle)
          && other.info.equals(info)
          && other.perfEventType.equals(perfEventType);
    }
  }

  private static class StartedImpl extends Started {

    private boolean isChainFinished = false;

    /** @return whether creating new events off of this chain is allowed. */
    @JsonIgnore
    public boolean isChainFinished() {
      return isChainFinished;
    }

    /** Called to indicate that the chain should not allow any further events. */
    public void markChainFinished() {
      isChainFinished = true;
    }

    @Override
    public SimplePerfEvent createUpdateEvent(ImmutableMap<String, Object> info) {
      Preconditions.checkState(!isChainFinished());
      return new Updated(this, info);
    }

    @Override
    public SimplePerfEvent createUpdateEvent(String k1, Object v1) {
      Preconditions.checkState(!isChainFinished());
      return createUpdateEvent(ImmutableMap.of(k1, v1));
    }

    @Override
    public SimplePerfEvent createUpdateEvent(String k1, Object v1, String k2, Object v2) {
      Preconditions.checkState(!isChainFinished());
      return createUpdateEvent(ImmutableMap.of(k1, v1, k2, v2));
    }

    @Override
    public SimplePerfEvent createFinishedEvent(ImmutableMap<String, Object> info) {
      Preconditions.checkState(!isChainFinished());
      return new Finished(this, info);
    }

    @Override
    public SimplePerfEvent createFinishedEvent() {
      Preconditions.checkState(!isChainFinished());
      return createFinishedEvent(ImmutableMap.of());
    }

    @Override
    public SimplePerfEvent createFinishedEvent(String k1, Object v1) {
      Preconditions.checkState(!isChainFinished());
      return createFinishedEvent(ImmutableMap.of(k1, v1));
    }

    @Override
    public SimplePerfEvent createFinishedEvent(String k1, Object v1, String k2, Object v2) {
      Preconditions.checkState(!isChainFinished());
      return createFinishedEvent(ImmutableMap.of(k1, v1, k2, v2));
    }

    public StartedImpl(PerfEventTitle perfEventTitle, ImmutableMap<String, Object> info) {
      super(EventKey.unique(), perfEventTitle, Type.STARTED, info);
    }

    public StartedImpl(
        PerfEventTitle perfEventTitle, String category, ImmutableMap<String, Object> info) {
      super(EventKey.unique(), perfEventTitle, Type.STARTED, category, info);
    }
  }

  private static class Updated extends AbstractChainablePerfEvent {

    public Updated(StartedImpl started, ImmutableMap<String, Object> updateInfo) {
      super(started.getEventKey(), started.getTitle(), Type.UPDATED, updateInfo);
    }

    public Updated(StartedImpl started, String category, ImmutableMap<String, Object> updateInfo) {
      super(started.getEventKey(), started.getTitle(), Type.UPDATED, category, updateInfo);
    }
  }

  public static class Finished extends AbstractChainablePerfEvent {

    public Finished(StartedImpl started, ImmutableMap<String, Object> finishedInfo) {
      super(
          started.getEventKey(),
          started.getTitle(),
          Type.FINISHED,
          started.getCategory(),
          finishedInfo);
      started.markChainFinished();
    }
  }
}
