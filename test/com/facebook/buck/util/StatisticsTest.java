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

import static org.junit.Assert.assertEquals;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.junit.Test;

public class StatisticsTest {

  @Test
  public void testN() {
    Statistics stats = new Statistics();

    assertEquals(0, stats.getN());

    stats.addValue(0);
    assertEquals(1, stats.getN());

    stats.addValue(1);
    assertEquals(2, stats.getN());

    stats.addValue(2);
    assertEquals(3, stats.getN());

    stats.addValue(3);
    assertEquals(4, stats.getN());

    for (int i = 0; i < 1000; i++) {
      stats.addValue(i);
      assertEquals(5 + i, stats.getN());
    }
  }

  @Test
  public void testMean() {
    Statistics stats = new Statistics();

    for (int i = 0; i < 101; i++) {
      stats.addValue(i);
    }

    // (101 * (101 - 1) / 2) / 101
    assertEquals(50, stats.getMean(), 0.001);
  }

  @Test
  public void testStandardDeviation() {
    Statistics stats = new Statistics();
    // We get to use the real commons-math version in tests.
    SummaryStatistics commonsStats = new SummaryStatistics();

    for (int v : new int[] {13, 16, 8, 9, 12, 19, 22}) {
      stats.addValue(v);
      commonsStats.addValue(v);
    }

    // Calculated at
    // https://www.calculator.net/standard-deviation-calculator.html?numberinputs=13%2C+16%2C+8%2C+9%2C+12%2C+19%2C+22&x=77&y=16
    assertEquals(commonsStats.getMean(), stats.getMean(), 0.00000001);
    assertEquals(commonsStats.getVariance(), stats.getVariance(), 0.00000001);
    assertEquals(commonsStats.getStandardDeviation(), stats.getStandardDeviation(), 0.00000001);

    // Calculated at https://www.mathsisfun.com/data/confidence-interval-calculator.html
    // They are kind enough to also hardcode the not quite correct value of 1.96
    assertEquals(3.81, stats.getConfidenceIntervalOffset(), 0.01);
  }
}
