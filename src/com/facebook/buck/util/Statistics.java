/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.util;

/** A crappy version of {@link org.apache.commons.math3.stat.descriptive.SummaryStatistics}. */
// We'd love to use commons-math's SummaryStatistics, but commons-math is 2.5MB.
// For algorithm, see
// https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Welford's_online_algorithm
public class Statistics {
  long count = 0;
  double mean = 0;
  double m2 = 0;

  /** Add a value to the data. */
  public void addValue(long millis) {
    count += 1;
    double delta = millis - mean;
    mean += delta / count;
    double delta2 = millis - mean;
    m2 += delta2 * delta;
  }

  /** Get the count of values. */
  public long getN() {
    return count;
  }

  /** Get the mean. */
  public double getMean() {
    return mean;
  }

  /** Get the standard deviation. */
  public double getVariance() {
    return m2 / (getN() - 1);
  }

  /** Get the standard deviation. */
  public double getStandardDeviation() {
    return Math.sqrt(getVariance());
  }

  /**
   * Get the 97.5% z-score. [ getMean() - getZScore(), getMean() + getZscore()] gives approximately
   * a nice 95% confidence interval for the mean.
   */
  public double getConfidenceIntervalOffset() {
    // See https://en.wikipedia.org/wiki/1.96 and
    // http://www.stat.yale.edu/Courses/1997-98/101/confint.htm

    // I don't think this is quite correct, the value should be based on N, but that's okay it's
    // close enough.
    return 1.96 * getStandardDeviation() / Math.sqrt(getN());
  }
}
