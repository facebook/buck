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

package com.facebook.buck.support;

import com.facebook.buck.core.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.GuardedBy;

/**
 * TopSlowTargetsBuilder is a builder that reacts to the completion of {@link BuildTarget}s and
 * records the top slow targets that it has seen. TopSlowTargetsBuilder efficiently tracks the top N
 * build targets that it sees, where N is a parameter to the constructor.
 *
 * <p>In terms of implementation strategy, this class keeps a doubly-linked list of slow targets of
 * length <= N at all times. This strategy minimizes the amount of bookkeeping that is done, keeping
 * it proportional to N, and also allows us to efficiently skip processing targets that aren't in
 * the top N rules that we've currently seen.
 */
public final class TopSlowTargetsBuilder {

  /** The head pointer of the slow target linked list. */
  @GuardedBy("this")
  private SlowTargetCandidate slowestTarget = null;

  /** The tail pointer of the slow target linked list. */
  @GuardedBy("this")
  private SlowTargetCandidate slowestTargetTail = null;

  /**
   * The number of targets currently in the slow target linked list. Always in the range of 0 <=
   * targetCount + 1.
   */
  @GuardedBy("this")
  private int slowTargetsLength;

  /** The number of top slow rules to track (a.k.a N), a parameter to the constructor. */
  private final int targetCount;

  /** Constructs a TopSlowTargetsBuilder with a N of 10. */
  public TopSlowTargetsBuilder() {
    this(10);
  }

  /**
   * Constructs a TopSlowTargetsBuilder that keeps track of the top ruleCount slow rules.
   *
   * @param ruleCount The number of top slow rules to keep track of.
   */
  public TopSlowTargetsBuilder(int ruleCount) {
    this.targetCount = ruleCount;
    this.slowTargetsLength = 0;
  }

  /**
   * Records the given target with associated execution time. If this target is one of the top N
   * slow rules that we've seen, it's recorded in the top slow rules linked list.
   *
   * @param target The target that has just finished executing
   * @param durationMilliseconds The execution duration of the given target
   */
  public synchronized void onTargetCompleted(BuildTarget target, long durationMilliseconds) {
    SlowTargetCandidate newCandidate = new SlowTargetCandidate(target, durationMilliseconds);
    insertCandidate(newCandidate);
    trimToRequestedLength();
  }

  /**
   * Inserts a candidate slow node into the linked list. At the end of this method,
   * slowTargetsLength might be larger than targetCount; it's up to {@link #trimToRequestedLength()}
   * to drop excess nodes from the list.
   *
   * @param candidate The candidate slow target node
   */
  private void insertCandidate(SlowTargetCandidate candidate) {
    if (slowestTarget == null) {
      slowestTarget = slowestTargetTail = candidate;
      slowTargetsLength++;
      return;
    }

    Preconditions.checkState(slowestTargetTail != null);
    if (slowTargetsLength == targetCount
        && candidate.getDurationMilliseconds() < slowestTargetTail.getDurationMilliseconds()) {
      return;
    }

    SlowTargetCandidate cursor = slowestTarget;
    while (cursor != null) {
      if (cursor.getDurationMilliseconds() < candidate.getDurationMilliseconds()) {
        candidate.setNextSlowestTarget(cursor);
        if (cursor.getPrevSlowestTarget() != null) {
          cursor.getPrevSlowestTarget().setNextSlowestTarget(candidate);
        } else {
          slowestTarget = candidate;
        }

        cursor.setPrevSlowestTarget(candidate);
        slowTargetsLength++;
        return;
      }

      cursor = cursor.getNextSlowestTarget();
    }

    slowestTargetTail.setNextSlowestTarget(candidate);
    candidate.setPrevSlowestTarget(slowestTargetTail);
    slowestTargetTail = candidate;
    slowTargetsLength++;
  }

  /**
   * Drops any excess nodes from the slow target list, if the number of nodes in the list exceeds
   * {@link #targetCount}.
   */
  private void trimToRequestedLength() {
    if (slowTargetsLength > targetCount) {
      Preconditions.checkState(slowTargetsLength == targetCount + 1);
      slowestTargetTail = slowestTargetTail.getPrevSlowestTarget();
      if (slowestTargetTail != null) {
        slowestTargetTail.setNextSlowestTarget(null);
      }
    }
  }

  /** Returns a list of the top N slow rules that this builder has observed. */
  public synchronized ImmutableList<SlowTarget> getSlowRules() {
    ImmutableList.Builder<SlowTarget> builder =
        ImmutableList.builderWithExpectedSize(slowTargetsLength);
    SlowTargetCandidate cursor = slowestTarget;
    while (cursor != null) {
      builder.add(ImmutableSlowTarget.ofImpl(cursor.getTarget(), cursor.getDurationMilliseconds()));
      cursor = cursor.getNextSlowestTarget();
    }

    return builder.build();
  }
}
