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

import com.facebook.buck.core.rulekey.RuleKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;

public abstract class ArtifactCompressionEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {
  public enum Operation {
    COMPRESS,
    DECOMPRESS,
  }

  private final Operation operation;
  @JsonIgnore private final ImmutableSet<RuleKey> ruleKeys;

  protected ArtifactCompressionEvent(
      EventKey eventKey, Operation operation, ImmutableSet<RuleKey> ruleKeys) {
    super(eventKey);
    this.operation = operation;
    this.ruleKeys = ruleKeys;
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

  public static Started started(Operation operation, ImmutableSet<RuleKey> ruleKeys) {
    return new Started(operation, ruleKeys);
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  public static class Started extends ArtifactCompressionEvent {
    protected Started(Operation operation, ImmutableSet<RuleKey> ruleKeys) {
      super(EventKey.unique(), operation, ruleKeys);
    }

    @Override
    public String getEventName() {
      return String.format(
          "Artifact%sStarted",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }

  public static class Finished extends ArtifactCompressionEvent {
    protected Finished(Started started) {
      super(started.getEventKey(), started.getOperation(), started.getRuleKeys());
    }

    @Override
    public String getEventName() {
      return String.format(
          "Artifact%sFinished",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }
}
