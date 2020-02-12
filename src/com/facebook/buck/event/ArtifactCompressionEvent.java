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

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.HasNameAndType;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;

/** Event for artifact compression / decompression */
public abstract class ArtifactCompressionEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {
  public enum Operation {
    COMPRESS,
    DECOMPRESS,
  }

  private final Operation operation;
  @JsonIgnore private final ImmutableSet<RuleKey> ruleKeys;

  @JsonView(JsonViews.MachineReadableLog.class)
  private final HasNameAndType buildRule;

  protected ArtifactCompressionEvent(
      EventKey eventKey, Operation operation, ImmutableSet<RuleKey> ruleKeys, HasNameAndType rule) {
    super(eventKey);
    this.operation = operation;
    this.ruleKeys = ruleKeys;
    this.buildRule = rule;
  }

  @Override
  public String getCategory() {
    return "artifact_" + operation.toString().toLowerCase();
  }

  @Override
  public String getValueString() {
    return String.format(
        "%s:%s", operation.toString().toLowerCase(), Joiner.on(",").join(ruleKeys));
  }

  public ImmutableSet<RuleKey> getRuleKeys() {
    return ruleKeys;
  }

  public Operation getOperation() {
    return operation;
  }

  /** Create a new Started event for the operation and set of RuleKeys */
  public static Started started(
      Operation operation, ImmutableSet<RuleKey> ruleKeys, HasNameAndType rule) {
    return new Started(operation, ruleKeys, rule);
  }

  /** Create a new Finished event for corresponding Started event */
  public static Finished finished(
      Started started, long fullSize, long compressedSize, HasNameAndType rule) {
    return new Finished(started, fullSize, compressedSize, rule);
  }

  /** Event for when a artifact starts compression/decompression */
  public static class Started extends ArtifactCompressionEvent {
    protected Started(Operation operation, ImmutableSet<RuleKey> ruleKeys, HasNameAndType rule) {
      super(EventKey.unique(), operation, ruleKeys, rule);
    }

    @Override
    public String getEventName() {
      return String.format(
          "Artifact%sStarted",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }

  /** Event for when a artifact finishes compression/decompression */
  public static class Finished extends ArtifactCompressionEvent {
    protected Finished(Started started, long fullSize, long compressedSize, HasNameAndType rule) {
      super(started.getEventKey(), started.getOperation(), started.getRuleKeys(), rule);
      startedTimeStamp = started.getTimestampMillis();
      this.fullSize = fullSize;
      this.compressedSize = compressedSize;
    }

    private final long startedTimeStamp;

    @JsonView(JsonViews.MachineReadableLog.class)
    public final long fullSize;

    @JsonView(JsonViews.MachineReadableLog.class)
    public final long compressedSize;

    /** Returns the timestamp of corresponding started event */
    public long getStartedTimeStamp() {
      return startedTimeStamp;
    }

    @Override
    public String getEventName() {
      return String.format(
          "Artifact%sFinished",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }
}
