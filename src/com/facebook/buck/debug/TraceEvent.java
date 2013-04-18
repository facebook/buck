/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.debug;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

/**
 * Single event for a trace.
 */
class TraceEvent {

  private final long id;
  private final String comment;
  private final String type;
  private final long eventTime;
  private final long startTime;

  TraceEvent(long id, String comment, String type, long startTime) {
    this.id = id;
    this.comment = comment;
    this.type = type;
    this.eventTime = System.currentTimeMillis();
    this.startTime = startTime;
  }

  public long getId() {
    return id;
  }

  public String getComment() {
    return comment;
  }

  public String getType() {
    return type;
  }

  public long getEventTime() {
    return eventTime;
  }

  /**
   * Returns a formatted string for the event.
   *
   * @param traceStartTime the start time of the trace to generate relative times
   * @param prevTime the completion time of the previous event or -1
   * @param indent extra indent for the message if there was no previous event.
   * @return the formatted tracer string
   */
  String toTraceString(long traceStartTime, long prevTime, String indent) {
    // TODO(user): move to ThreadTrace to split formatting from representation
    StringBuilder sb = new StringBuilder();

    if (prevTime == -1) {
      sb.append("-----");
    } else {
      sb.append(longToPaddedString(this.eventTime - prevTime));
    }

    sb.append(" ");
    sb.append(formatTime(this.eventTime - traceStartTime));
    if (this instanceof TraceStart) {
      sb.append(" Start         ");
    } else if (this instanceof TraceStop) {
      sb.append(" Done ");
      long delta = eventTime - startTime;
      sb.append(longToPaddedString(delta));
      sb.append(" ms ");
    } else {
      sb.append(" Comment      ");
    }

    sb.append(indent);
    sb.append(this.toString());
    return sb.toString();
  }

  /**
   * @return A string describing the tracer event.
   */
  @Override
  public String toString() {
    if (this.type == null) {
      return comment;
    } else {
      return String.format("[%s] %s", type, comment);
    }
  }

  /**
   * Converts {@code value} to a string and pads it with up to 4 spaces for improved alignment.
   * @param value A nonnegative value.
   * @return A padded string.
   */
  static String longToPaddedString(long value) {
    Preconditions.checkArgument(value >= 0, "value must be positive");
    return Strings.padStart(String.valueOf(value), 5, '0');
  }

  /**
   * Return the sec.ms part of time (if time = "20:06:11.566", "11.566")
   *
   * @param time The time in milliseconds.
   * @return A formatted string as "sec.ms".
   */
  static String formatTime(long time) {
    String sec = String.valueOf(Math.max(0, (time / 1000) % 60));
    String ms = String.valueOf(Math.max(time % 1000, 0));
    return Strings.padStart(sec, 2, '0') + '.' + Strings.padStart(ms, 3, '0');
  }

}
