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

package com.facebook.buck.java;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.CaseFormat;
import com.google.common.base.Objects;

/**
 * Base class for events about Java annotation processing.
 */
public abstract class AnnotationProcessingEvent extends AbstractBuckEvent implements LeafEvent {

  public enum Operation {
    GET_COMPLETIONS,
    GET_SUPPORTED_ANNOTATION_TYPES,
    GET_SUPPORTED_OPTIONS,
    GET_SUPPORTED_SOURCE_VERSION,
    INIT,
    PROCESS,
  }

  private final BuildTarget buildTarget;
  private final String annotationProcessorName;
  private final Operation operation;
  private final int round;
  private final boolean isLastRound;

  protected AnnotationProcessingEvent(
      BuildTarget buildTarget,
      String annotationProcessorName,
      Operation operation,
      int round,
      boolean isLastRound) {
    this.buildTarget = buildTarget;
    this.annotationProcessorName = annotationProcessorName;
    this.operation = operation;
    this.round = round;
    this.isLastRound = isLastRound;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public String getAnnotationProcessorName() {
    return annotationProcessorName;
  }

  public Operation getOperation() {
    return operation;
  }

  public int getRound() {
    return round;
  }

  public boolean isLastRound() {
    return isLastRound;
  }

  @Override
  protected String getValueString() {
    return buildTarget.toString();
  }

  @Override
  public String getCategory() {
    return annotationProcessorName +
        "." +
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, operation.toString());
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof AnnotationProcessingEvent)) {
      return false;
    }

    AnnotationProcessingEvent that = (AnnotationProcessingEvent) event;

    return Objects.equal(getBuildTarget(), that.getBuildTarget()) &&
        Objects.equal(getAnnotationProcessorName(), that.getAnnotationProcessorName()) &&
        Objects.equal(getOperation(), that.getOperation()) &&
        getRound() == that.getRound();

    // We don't include isLastRound in the comparison because it's a property of the round, and
    // we already compare the round
  }

  public static Started started(
      BuildTarget buildTarget,
      String annotationProcessorName,
      Operation operation,
      int round,
      boolean isLastRound) {
    return new Started(buildTarget, annotationProcessorName, operation, round, isLastRound);
  }

  public static Finished finished(
      BuildTarget buildTarget,
      String annotationProcessorName,
      Operation operation,
      int round,
      boolean isLastRound) {
    return new Finished(buildTarget, annotationProcessorName, operation, round, isLastRound);
  }

  public static class Started extends AnnotationProcessingEvent {
    public Started(
        BuildTarget buildTarget,
        String annotationProcessorName,
        Operation operation,
        int round,
        boolean isLastRound) {
      super(buildTarget, annotationProcessorName, operation, round, isLastRound);
    }

    @Override
    public String getEventName() {
      return String.format(
          "%s.%sStarted",
          getAnnotationProcessorName(),
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, getOperation().toString()));
    }
  }

  public static class Finished extends AnnotationProcessingEvent {
    public Finished(
        BuildTarget buildTarget,
        String annotationProcessorName,
        Operation operation,
        int round,
        boolean isLastRound) {
      super(buildTarget, annotationProcessorName, operation, round, isLastRound);
    }

    @Override
    public String getEventName() {
      return String.format(
          "%s.%sFinished",
          getAnnotationProcessorName(),
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, getOperation().toString()));
    }
  }
}
