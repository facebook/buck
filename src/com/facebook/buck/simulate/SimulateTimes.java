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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Contains the times to be used for each BuildTarget during the simulation.
 */
public class SimulateTimes {
  private final ImmutableMap<String, Long> buildTargetTimes;
  private final String file;
  private final String timeType;
  private final long defaultMillis;

  private SimulateTimes(
      ImmutableMap<String, Long> buildTargetTimes,
      String file,
      String timeType,
      long defaultMillis) {
    this.buildTargetTimes = buildTargetTimes;
    this.file = file;
    this.timeType = timeType;
    this.defaultMillis = defaultMillis;
  }

  public static SimulateTimes createEmpty(long defaultMillis) {
    return new SimulateTimes(
        ImmutableMap.<String, Long>of(),
        "",
        "",
        defaultMillis);
  }

  public static SimulateTimes createFromJsonFile(
      ObjectMapper jsonConverter,
      String fileName,
      String timeType,
      long defaultMillis)
      throws IOException {
    File file = new File(fileName);
    JsonFileContent fileContent = new JsonFileContent();
    jsonConverter.readerForUpdating(fileContent).readValue(file);
    Map<String, Long> timings = Maps.newHashMap();
    Map<String, Map<String, Long>> allTimings = fileContent.getBuildTargetTimes();
    for (String key : allTimings.keySet()) {
      Map<String, Long> timesForKey = Preconditions.checkNotNull(allTimings.get(key));
      if (timesForKey.containsKey(timeType)) {
        timings.put(key, timesForKey.get(timeType));
      }
    }

    SimulateTimes times = new SimulateTimes(
        ImmutableMap.copyOf(timings),
        file.getName(),
        timeType,
        defaultMillis);
    return times;
  }

  /**
   * @param buildTarget
   * @return the specific millis duration for the buildTarget argument or the default value if
   *         this is not present.
   */
  public long getMillisForTarget(String buildTarget) {
    if (buildTargetTimes.containsKey(buildTarget)) {
      return buildTargetTimes.get(buildTarget);
    }

    return getDefaultMillis();
  }

  public long getDefaultMillis() {
    return defaultMillis;
  }

  public boolean hasMillisForTarget(String buildTarget) {
    return buildTargetTimes.containsKey(buildTarget);
  }

  public String getFile() {
    return file;
  }

  public String getTimeType() {
    return timeType;
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
