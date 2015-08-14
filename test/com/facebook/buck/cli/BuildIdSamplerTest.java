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

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.Pair;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class BuildIdSamplerTest {

  ImmutableMap<BuildId, Float> buildIdToExpectedHashMap = ImmutableMap.of(
      new BuildId("0a0a1967-5797-c4fa-6c32-b90c45516c6d"), 0.09f,
      new BuildId("6d093717-b445-baf3-2863-64b6d71ebdac"), 0.56f,
      new BuildId("a8d6975f-2859-f12e-559c-4486da52F9aK"), 1.0f,
      new BuildId("a8d6975f-2859-f12e-559c-4486da52F9aM"), 0.0f
  );

  @Test
  public void testRejectAllSampler() {
    BuildIdSampler rejectAllSampler = new BuildIdSampler(0);
    for (BuildId buildId : buildIdToExpectedHashMap.keySet()) {
      assertThat(
          String.format("BuildId %s", buildId),
          rejectAllSampler.apply(buildId),
          Matchers.equalTo(false)
      );
    }
  }

  @Test
  public void testAcceptAllSampler() {
    BuildIdSampler acceptAllSampler = new BuildIdSampler(1.0f);
    for (BuildId buildId : buildIdToExpectedHashMap.keySet()) {
      assertThat(
          String.format("BuildId %s", buildId),
          acceptAllSampler.apply(buildId),
          Matchers.equalTo(true)
      );
    }
  }

  @Test
  public void testAcceptHalf() {
    BuildIdSampler sampler = BuildIdSampler.CREATE_FUNCTION.apply(0.5f);
    for (BuildId buildId : buildIdToExpectedHashMap.keySet()) {
      boolean shouldAccept = buildIdToExpectedHashMap.get(buildId) < 0.5f;
      assertThat(
          String.format("BuildId %s", buildId),
          sampler.apply(buildId),
          Matchers.equalTo(shouldAccept)
      );
    }
  }

  @Test
  public void testIsUniformish() {
    final int iterations = 5000;
    final float fudge = 0.01f;
    final int bucketCount = 10;

    List<Pair<BuildIdSampler, AtomicInteger>> buckets = new ArrayList<>();
    for (int i = 1; i <= bucketCount; ++i) {
      buckets.add(new Pair<BuildIdSampler, AtomicInteger>(
              new BuildIdSampler(((float) i) / bucketCount),
              new AtomicInteger(0)));
    }

    Random random = new Random(0);
    for (int i = 0; i < iterations; ++i) {
      UUID uuid = new UUID(random.nextLong(), random.nextLong());
      BuildId buildId = new BuildId(uuid.toString());
      for (Pair<BuildIdSampler, AtomicInteger> entry : buckets) {
        if (entry.getFirst().apply(buildId)) {
          entry.getSecond().incrementAndGet();
          break;
        }
      }
    }

    for (Pair<BuildIdSampler, AtomicInteger> bucketPair : buckets) {
      float bucketSize = ((float) bucketPair.getSecond().get()) / iterations;
      assertThat(
          bucketSize,
          Matchers.allOf(
              Matchers.greaterThan(0.1f - fudge),
              Matchers.lessThan(0.1f + fudge)));
    }
  }
}
