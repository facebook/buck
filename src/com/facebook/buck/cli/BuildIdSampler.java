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

package com.facebook.buck.cli;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.util.SampleRate;
import com.google.common.base.Preconditions;
import java.util.function.Function;

/** Decides whether a particular {@link BuildId} belongs to a sampling group of a supplied size. */
public class BuildIdSampler implements Function<BuildId, Boolean> {

  private SampleRate sampleRate;

  /**
   * @param sampleRate value in percent between 0 and 100 inclusive. This decides the probability of
   *     a random {@link BuildId} belongin to the sample.
   */
  public BuildIdSampler(SampleRate sampleRate) {
    this.sampleRate = sampleRate;
  }

  /**
   * @param buildId {@link BuildId} to test.
   * @return whether the supplied {@link BuildId} belongs to the sample.
   */
  @Override
  public Boolean apply(BuildId buildId) {
    return apply(sampleRate, buildId);
  }

  /**
   * @param sampleRate It decides the probability of a random {@link BuildId} belonging to the
   *     sample.
   * @param buildId {@link BuildId} to test.
   * @return Whether the supplied {@link BuildId} belongs to the sample.
   */
  public static boolean apply(SampleRate sampleRate, BuildId buildId) {
    if (sampleRate.getSampleRate() == 0.0f) {
      return false;
    } else if (sampleRate.getSampleRate() == 1.0f) {
      return true;
    }

    String buildIdString = buildId.toString();
    // Naive hashcode implementation so we don't rely on the platform's hashcode implementation.
    int hash = 7;
    for (int i = 0; i < buildIdString.length(); ++i) {
      hash = hash * 31 + buildIdString.charAt(i);
    }
    long range = ((long) Integer.MAX_VALUE) - ((long) Integer.MIN_VALUE);
    long adjustedHash = ((long) hash) - ((long) Integer.MIN_VALUE);
    float scaledHash = ((float) adjustedHash) / range;
    Preconditions.checkState(scaledHash <= 1.0f && scaledHash >= 0.0f);
    return sampleRate.getSampleRate() > scaledHash;
  }
}
