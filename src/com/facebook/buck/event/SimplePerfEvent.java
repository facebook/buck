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

package com.facebook.buck.event;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

/**
 * An implementation of {@link BuckEvent}s used for gathering performance statistics. These are
 * only intended to be used with the trace viewer and should not be used to communicate
 * information between parts of the system.
 */
public abstract class SimplePerfEvent extends AbstractBuckEvent {

  public SimplePerfEvent(EventKey eventKey) {
    super(eventKey);
  }

  public enum Type {
    STARTED("Started"),
    UPDATED("Updated"),
    FINISHED("Finished"),
    ;

    private String value;

    public String getValue() {
      return value;
    }

    Type(String value) {
      this.value = value;
    }
  }

  /**
   * @return event identifier.
   */
  public abstract PerfEventId getEventId();

  /**
   * @return event type.
   */
  public abstract Type getEventType();

  /**
   * @return information associated with the event.
   */
  public abstract ImmutableMap<String, Object> getEventInfo();

  /**
   * Prefer using {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}
   * when possible.
   *
   * Create an event that indicates the start of a particular operation.
   *
   * @param perfEventId identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param info Any additional information to be saved with the event. This will be serialized and
   *             potentially sent over the wire, so please keep this small.
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(
      PerfEventId perfEventId,
      ImmutableMap<String, Object> info) {
    return new StartedImpl(perfEventId, info);
  }

  /**
   * Convenience wrapper around {@link SimplePerfEvent#started(PerfEventId, ImmutableMap)}.
   *
   * @param perfEventId identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(PerfEventId perfEventId) {
    return started(perfEventId, ImmutableMap.<String, Object>of());
  }

  /**
   * Convenience wrapper around {@link SimplePerfEvent#started(PerfEventId, ImmutableMap)}.
   *
   * @param perfEventId identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param k1 name of the value to be stored with the event.
   * @param v1 value to be stored. This will be serialized and potentially sent over the wire,
   *           so please keep this small.
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(PerfEventId perfEventId, String k1, Object v1) {
    return started(perfEventId, ImmutableMap.of(k1, v1));
  }

  /**
   * Convenience wrapper around {@link SimplePerfEvent#started(PerfEventId, ImmutableMap)}.
   *
   * @param perfEventId identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param k1 name of the value to be stored with the event.
   * @param v1 value to be stored. This will be serialized and potentially sent over the wire,
   *           so please keep this small.
   * @param k2 name of the value to be stored with the event.
   * @param v2 value to be stored. This will be serialized and potentially sent over the wire,
   *           so please keep this small.
   * @return an object that should be used to create the corresponding update and finished event.
   */
  public static Started started(
      PerfEventId perfEventId,
      String k1,
      Object v1,
      String k2,
      Object v2) {
    return started(perfEventId, ImmutableMap.of(k1, v1, k2, v2));
  }

  /**
   * Creates a scope within which the measured operation takes place.
   *
   * {@code
   * try (perfEvent = SimplePerfEvent.scope(bus, PerfEventId.of("BurnCpu"))) {
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
   * }
   *
   * @param bus the {@link BuckEventBus} to post update and finished events to.
   * @param perfEventId identifier of the operation (Upload, CacheFetch, Parse, etc..).
   * @param info Any additional information to be saved with the event. This will be serialized and
   *             potentially sent over the wire, so please keep this small.
   * @return an AutoCloseable which will send the finished event as soon as control flow exits
   * the scope.
   */
  public static Scope scope(
      BuckEventBus bus,
      PerfEventId perfEventId,
      ImmutableMap<String, Object> info) {
    StartedImpl started = new StartedImpl(perfEventId, info);
    bus.post(started);
    return new SimplePerfEventScope(bus, started);
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      BuckEventBus bus,
      PerfEventId perfEventId) {
    return scope(bus, perfEventId, ImmutableMap.<String, Object>of());
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      BuckEventBus bus,
      PerfEventId perfEventId,
      String k1,
      Object v1) {
    return scope(bus, perfEventId, ImmutableMap.of(k1, v1));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      BuckEventBus bus,
      PerfEventId perfEventId,
      String k1,
      Object v1,
      String k2,
      Object v2) {
    return scope(bus, perfEventId, ImmutableMap.of(k1, v1, k2, v2));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      Optional<BuckEventBus> bus,
      PerfEventId perfEventId,
      ImmutableMap<String, Object> info) {
    if (bus.isPresent()) {
      return scope(bus.get(), perfEventId, info);
    } else {
      return new NoopScope();
    }
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      Optional<BuckEventBus> bus,
      PerfEventId perfEventId) {
    return scope(bus, perfEventId, ImmutableMap.<String, Object>of());
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      Optional<BuckEventBus> bus,
      PerfEventId perfEventId,
      String k,
      Object v) {
    return scope(bus, perfEventId, ImmutableMap.of(k, v));
  }

  /**
   * Convenience wrapper for {@link SimplePerfEvent#scope(BuckEventBus, PerfEventId, ImmutableMap)}.
   */
  public static Scope scope(
      Optional<BuckEventBus> bus,
      PerfEventId perfEventId,
      String k,
      Object v,
      String k2,
      Object v2) {
    return scope(bus, perfEventId, ImmutableMap.of(k, v, k2, v2));
  }

  /**
   * Represents the scope within which a particular performance operation is taking place.
   */
  public interface Scope extends AutoCloseable {
    /**
     * Creates and sends an event which indicates an update in state of the scope. Every invocation
     * creates and sends a new event.
     * @param info Any additional information to be saved with the event. This will be serialized
     *             and potentially sent over the wire, so please keep this small.
     */
    void update(ImmutableMap<String, Object> info);

    /**
     * Convenience wrapper for {@link Scope#update(ImmutableMap)}.
     */
    void update(String k1, Object v1);

    /**
     * Convenience wrapper for {@link Scope#update(ImmutableMap)}.
     */
    void update(String k1, Object v1, String k2, Object v2);

    /**
     * Appends information to the finished event which will be sent when control exits the scope.
     * @param k1 name of the value to be stored with the event.
     * @param v1 value to be stored. This will be serialized and potentially sent over the wire,
     *           so please keep this small.
     */
    void appendFinishedInfo(String k1, Object v1);

    @Override
    void close();
  }

  public interface Started extends BuckEvent {
    /**
     * Creates a new event which indicates an update to the performance data being gathered.
     *
     * @param info Any additional information to be saved with the event. This will be serialized
     *             and potentially sent over the wire, so please keep this small.
     * @return An event which should be posted to the {@link BuckEventBus}.
     */
    BuckEvent createUpdateEvent(ImmutableMap<String, Object> info);

    /**
     * Convenience wrapper for
     * {@link Started#createUpdateEvent(String, Object)}.
     */
    BuckEvent createUpdateEvent(String k1, Object v1);

    /**
     * Convenience wrapper for
     * {@link Started#createUpdateEvent(String, Object)}.
     */
    BuckEvent createUpdateEvent(String k1, Object v1, String k2, Object v2);

    /**
     * Creates a new event which indicates the end of a performance event.
     *
     * @param info Any additional information to be saved with the event. This will be serialized
     *             and potentially sent over the wire, so please keep this small.
     * @return An event which should be posted to the {@link BuckEventBus}.
     */
    BuckEvent createFinishedEvent(ImmutableMap<String, Object> info);

    /**
     * Convenience wrapper for
     * {@link Started#createFinishedEvent(String, Object)}.
     */
    BuckEvent createFinishedEvent();

    /**
     * Convenience wrapper for
     * {@link Started#createFinishedEvent(String, Object)}.
     */
    BuckEvent createFinishedEvent(String k1, Object v1);

    /**
     * Convenience wrapper for
     * {@link Started#createFinishedEvent(String, Object)}.
     */
    BuckEvent createFinishedEvent(String k1, Object v1, String k2, Object v2);
  }

  @Value.Immutable
  @BuckStyleImmutable
  /**
   * This is an identifier for the various performance event names in use in the system. Should
   * be CamelCase (first letter capitalized).
   */
  abstract static class AbstractPerfEventId {
    @JsonValue
    @Value.Parameter
    public abstract String getValue();

    @Value.Check
    protected void nameIsNotEmpty() {
      Preconditions.checkArgument(!getValue().isEmpty());
    }
  }

  private static class NoopScope implements AutoCloseable, Scope {
    @Override
    public void update(ImmutableMap<String, Object> info) {
    }

    @Override
    public void update(String k1, Object v1) {
    }

    @Override
    public void update(String k1, Object v1, String k2, Object v2) {
    }

    @Override
    public void appendFinishedInfo(String k1, Object v1) {
    }

    @Override
    public void close() {
    }
  }

  private static class SimplePerfEventScope implements AutoCloseable, Scope {
    private BuckEventBus bus;
    private StartedImpl chainablePerfEvent;
    private ImmutableMap.Builder<String, Object> finishedInfoBuilder;

    public SimplePerfEventScope(
        BuckEventBus bus,
        StartedImpl chainablePerfEvent) {
      this.bus = bus;
      this.chainablePerfEvent = chainablePerfEvent;
      this.finishedInfoBuilder = ImmutableMap.builder();
    }

    @Override
    public void update(ImmutableMap<String, Object> info) {
      bus.post(new Updated(chainablePerfEvent, info));
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
    public void close() {
      bus.post(new Finished(chainablePerfEvent, finishedInfoBuilder.build()));
    }
  }

  /**
   * Common implementation for the Started/Updated/Finished events.
   */
  private abstract static class AbstractChainablePerfEvent extends SimplePerfEvent {
    private PerfEventId perfEventId;
    private Type perfEventType;
    private ImmutableMap<String, Object> info;

    public AbstractChainablePerfEvent(
        EventKey eventKey,
        PerfEventId perfEventId,
        Type perfEventType,
        ImmutableMap<String, Object> info) {
      super(eventKey);
      this.perfEventId = perfEventId;
      this.perfEventType = perfEventType;
      this.info = info;
    }

    @Override
    public PerfEventId getEventId() {
      return perfEventId;
    }

    @Override
    public Type getEventType() {
      return perfEventType;
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
      return new StringBuilder("PerfEvent")
          .append(perfEventId.getValue())
          .append(perfEventType.getValue())
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), perfEventId, perfEventType, info);
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      AbstractChainablePerfEvent other = (AbstractChainablePerfEvent) o;
      return other.perfEventId.equals(perfEventId) &&
          other.info.equals(info) &&
          other.perfEventType.equals(perfEventType);
    }
  }

  private static class StartedImpl extends AbstractChainablePerfEvent implements Started {
    private boolean isChainFinished = false;

    /**
     * @return whether creating new events off of this chain is allowed.
     */
    @JsonIgnore
    public boolean isChainFinished() {
      return isChainFinished;
    }

    /**
     * Called to indicate that the chain should not allow any further events.
     */
    public void markChainFinished() {
      isChainFinished = true;
    }

    @Override
    public BuckEvent createUpdateEvent(ImmutableMap<String, Object> info) {
      Preconditions.checkState(!isChainFinished());
      return new Updated(this, info);
    }

    @Override
    public BuckEvent createUpdateEvent(String k1, Object v1) {
      Preconditions.checkState(!isChainFinished());
      return createUpdateEvent(ImmutableMap.of(k1, v1));
    }

    @Override
    public BuckEvent createUpdateEvent(String k1, Object v1, String k2, Object v2) {
      Preconditions.checkState(!isChainFinished());
      return createUpdateEvent(ImmutableMap.of(k1, v1, k2, v2));
    }

    @Override
    public BuckEvent createFinishedEvent(ImmutableMap<String, Object> info) {
      Preconditions.checkState(!isChainFinished());
      return new Finished(this, info);
    }

    @Override
    public BuckEvent createFinishedEvent() {
      Preconditions.checkState(!isChainFinished());
      return createFinishedEvent(ImmutableMap.<String, Object>of());
    }

    @Override
    public BuckEvent createFinishedEvent(String k1, Object v1) {
      Preconditions.checkState(!isChainFinished());
      return createFinishedEvent(ImmutableMap.of(k1, v1));
    }

    @Override
    public BuckEvent createFinishedEvent(String k1, Object v1, String k2, Object v2) {
      Preconditions.checkState(!isChainFinished());
      return createFinishedEvent(ImmutableMap.of(k1, v1, k2, v2));
    }

    public StartedImpl(PerfEventId perfEventId, ImmutableMap<String, Object> info) {
      super(EventKey.unique(), perfEventId, Type.STARTED, info);
    }
  }

  private static class Updated extends AbstractChainablePerfEvent {

    public Updated(StartedImpl started, ImmutableMap<String, Object> updateInfo) {
      super(started.getEventKey(), started.getEventId(), Type.UPDATED, updateInfo);
    }
  }

  private static class Finished extends AbstractChainablePerfEvent {

    public Finished(StartedImpl started, ImmutableMap<String, Object> finishedInfo) {
      super(started.getEventKey(), started.getEventId(), Type.FINISHED, finishedInfo);
      started.markChainFinished();
    }
  }
}
