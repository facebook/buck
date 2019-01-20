/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.randomizedtrial;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildId;
import java.util.Map;
import java.util.TreeMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RandomizedTrialTest {

  public enum BrokenEnum implements WithProbability {
    GROUP1,
    GROUP2,
    ;

    @Override
    public double getProbability() {
      return 0.0;
    }
  }

  public enum MutableEnum implements WithProbability {
    GROUP1,
    GROUP2,
    ;

    public static double probabilityGroup1 = 0.0;
    public static double probabilityGroup2 = 0.0;

    @Override
    public double getProbability() {
      if (name().equals("GROUP1")) {
        return probabilityGroup1;
      } else {
        return probabilityGroup2;
      }
    }
  }

  public enum RegularEnum {
    GROUP1,
    GROUP2,
    ;
  }

  @Test
  public void testCreatingWithWrongConfiguration() {
    try {
      RandomizedTrial.getGroupStable("name", BrokenEnum.class);
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("misconfigured"));
      return;
    }
    throw new RuntimeException("Expected to fail");
  }

  @Test
  public void testPointStaysStable() {
    assertThat(
        RandomizedTrial.getPoint("test", "id"),
        Matchers.equalTo(RandomizedTrial.getPoint("test", "id")));
  }

  // The following test has caused some flakiness on Windows, so we disable this for now.
  //  @Test
  //  public void testPointDifferentForDifferentTests() throws Exception {
  //    assertThat(
  //        RandomizedTrial.getPoint("test1"),
  //        Matchers.not(Matchers.equalTo(RandomizedTrial.getPoint("test2"))));
  //  }

  @Test
  public void testGetGroupReturnsCorrectGroup() {
    BuildId buildId = new BuildId("01234");
    double point = RandomizedTrial.getPoint("name", buildId.toString());
    MutableEnum.probabilityGroup1 = point;
    MutableEnum.probabilityGroup2 = 1.0 - point;

    assertThat(
        RandomizedTrial.getGroup("name", buildId.toString(), MutableEnum.class),
        Matchers.equalTo(MutableEnum.GROUP2));
  }

  @Test
  public void testGetGroupStableReturnsCorrectGroup() {
    double point = RandomizedTrial.getPoint("name");
    MutableEnum.probabilityGroup1 = point;
    MutableEnum.probabilityGroup2 = 1.0 - point;

    assertThat(
        RandomizedTrial.getGroupStable("name", MutableEnum.class),
        Matchers.equalTo(MutableEnum.GROUP2));
  }

  @Test
  public void testGetGroupStableWithExperimentSetReturnsCorrectGroup() {
    double point = RandomizedTrial.getPoint("name");
    Map<RegularEnum, Double> enumValuesWithProbabilities = new TreeMap<>();
    enumValuesWithProbabilities.put(RegularEnum.GROUP1, point);
    enumValuesWithProbabilities.put(RegularEnum.GROUP2, 1.0 - point);

    assertThat(
        RandomizedTrial.getGroupStable("name", enumValuesWithProbabilities),
        Matchers.equalTo(RegularEnum.GROUP2));
  }

  @Test
  public void testGetGroupWithExperimentSetReturnsCorrectGroup() {
    BuildId buildId = new BuildId("01234");
    double point = RandomizedTrial.getPoint("name", buildId.toString());
    Map<RegularEnum, Double> enumValuesWithProbabilities = new TreeMap<>();
    enumValuesWithProbabilities.put(RegularEnum.GROUP1, point);
    enumValuesWithProbabilities.put(RegularEnum.GROUP2, 1.0 - point);

    assertThat(
        RandomizedTrial.getGroup("name", buildId.toString(), enumValuesWithProbabilities),
        Matchers.equalTo(RegularEnum.GROUP2));
  }
}
