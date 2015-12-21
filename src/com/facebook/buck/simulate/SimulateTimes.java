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

package com.facebook.buck.simulate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Contains the times to be used for each BuildTarget during the simulation.
 */
public class SimulateTimes {
  private static final String DEFAULT_TIME_AGGREGATE_KEY = "default";

  /**
   * The first map key corresponds to the [buildTarget] id and the second map key corresponds
   * to the [timeAggregate]. The value is the [timeMillis].
   */
  private final ImmutableMap<String, ImmutableMap<String, Long>> buildTargetTimes;
  private final String file;
  private final ImmutableSortedSet<String> timeAggregates;
  private final long ruleFallbackTimeMillis;

  private SimulateTimes(
      ImmutableMap<String, ImmutableMap<String, Long>> buildTargetTimes,
      ImmutableSortedSet<String> timeAggregates,
      String file,
      long defaultMillis) {
    this.buildTargetTimes = buildTargetTimes;
    this.file = file;
    Preconditions.checkState(
        !timeAggregates.contains(DEFAULT_TIME_AGGREGATE_KEY),
        "Time aggregate key '%s' is reserved and should not be used.",
        DEFAULT_TIME_AGGREGATE_KEY);
    this.timeAggregates = ImmutableSortedSet.<String>naturalOrder()
        .addAll(timeAggregates)
        .add(DEFAULT_TIME_AGGREGATE_KEY)
        .build();
    this.ruleFallbackTimeMillis = defaultMillis;
  }

  public static SimulateTimes createEmpty(long defaultMillis) {
    return new SimulateTimes(
        ImmutableMap.<String, ImmutableMap<String, Long>>of(),
        ImmutableSortedSet.<String>of(),
        "",
        defaultMillis);
  }

  public static SimulateTimes createFromJsonFile(
      ObjectMapper jsonConverter,
      String fileName,
      long defaultMillis)
      throws IOException {
    File file = new File(fileName);
    JsonFileContent fileContent = new JsonFileContent();
    jsonConverter.readerForUpdating(fileContent).readValue(file);
    LinkedHashSet<String> timeAggregates = Sets.newLinkedHashSet();
    Map<String, ImmutableMap<String, Long>> immutableTimeAggregates = Maps.newHashMap();
    Map<String, Map<String, Long>> allTimings = fileContent.getBuildTargetTimes();
    for (String targetName : allTimings.keySet()) {
      Map<String, Long> timesForKey = Preconditions.checkNotNull(allTimings.get(targetName));
      for (String timeAggregate : timesForKey.keySet()) {
        timeAggregates.add(timeAggregate);
      }

      immutableTimeAggregates.put(targetName, ImmutableMap.copyOf(timesForKey));
    }

    SimulateTimes times = new SimulateTimes(
        ImmutableMap.copyOf(immutableTimeAggregates),
        ImmutableSortedSet.copyOf(timeAggregates),
        file.getName(),
        defaultMillis);
    return times;
  }

  /**
   * @param buildTarget
   * @return the specific millis duration for the buildTarget argument or the default value if
   *         this is not present.
   */
  public long getMillisForTarget(String buildTarget, String timeAggregate) {
    if (hasMillisForTarget(buildTarget, timeAggregate)) {
      return Preconditions.checkNotNull(buildTargetTimes.get(buildTarget).get(timeAggregate));
    }

    return getRuleFallbackTimeMillis();
  }

  public long getRuleFallbackTimeMillis() {
    return ruleFallbackTimeMillis;
  }

  public boolean hasMillisForTarget(String buildTarget, String timeAggregate) {
    if (buildTargetTimes.containsKey(buildTarget)) {
      ImmutableMap<String, Long> timesForRule = Preconditions.checkNotNull(
          buildTargetTimes.get(buildTarget));
      if (timesForRule.containsKey(timeAggregate)) {
        return true;
      }
    }

    return false;
  }

  public String getFile() {
    return file;
  }

  public ImmutableList<String> getTimeAggregates() {
    return ImmutableList.copyOf(timeAggregates);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class JsonFileContent {
    @JsonProperty("build_targets")
    private Map<String, Map<String, Long>> buildTargetTimes;

    public JsonFileContent() {
      buildTargetTimes = Maps.newHashMap();
    }

    public Map<String, Map<String, Long>> getBuildTargetTimes() {
      return buildTargetTimes;
    }
  }
}
