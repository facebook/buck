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

package com.facebook.buck.randomizedtrial;

import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.hash.Hashing;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

/**
 * Simple implementation of A/B testing. Each RandomizedTrial selects a group to which buck instance
 * belongs to. This choice is stable and currently based on hostname, test name and user name.
 */
public class RandomizedTrial {
  private static final Supplier<String> HOSTNAME_SUPPLIER =
      Suppliers.memoize(
          () -> {
            try {
              return java.net.InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
              return "unable.to.determine.host";
            }
          });

  private static final Logger LOG = Logger.get(RandomizedTrial.class);

  private RandomizedTrial() {}

  /**
   * Returns a group for trial with given name.
   *
   * @param name name of trial.
   * @param enumClass Class of an enum which conforms to {@link WithProbability} interface.
   * @param defaultValue The value that will be used in case if class can't determine the group.
   */
  public static <T extends Enum<T> & WithProbability> T getGroup(
      String name, Class<T> enumClass, T defaultValue) {
    EnumSet<T> enumSet = EnumSet.allOf(enumClass);

    double sumOfAllProbabilities = 0;
    for (T value : enumSet) {
      sumOfAllProbabilities += value.getProbability();
    }
    Preconditions.checkArgument(
        sumOfAllProbabilities == 1.0,
        "RandomizedTrial '%s' is misconfigured: sum of probabilities of all groups must be "
            + "equal 1.0, but it is %f",
        name,
        sumOfAllProbabilities);

    double point = getPoint(name);

    double groupProbabilityLowPoint = 0.0;
    for (T value : enumSet) {
      double groupProbabilityHighPoint = groupProbabilityLowPoint + value.getProbability();
      if (point >= groupProbabilityLowPoint && point < groupProbabilityHighPoint) {
        LOG.debug("Test %s detected group %s", name, value);
        return value;
      } else {
        groupProbabilityLowPoint = groupProbabilityHighPoint;
      }
    }

    LOG.error(
        "Test %s was unable to detect group. Point is: %f. Groups: %s. Will use default value: %s",
        name, point, enumSet, defaultValue);
    return defaultValue;
  }

  @VisibleForTesting
  static double getPoint(String name) {
    String key = getKey(name);
    return getPointForKey(key);
  }

  /**
   * This method determines which double number in range of [0.0, 1.0] represents the given key.
   * Algorithm is: 1. Get SHA of the given key. 2. Get first digit of the SHA and convert it into
   * number, e.g. D -> 13. 3. Get 2 digits starting at position we got from step 2, e.g. A1. This is
   * to randomize the resulting value a bit more. 4. Convert these digits into number (A1->161) and
   * divide it by 255 (161/255=0.631) and return this value.
   *
   * @param key Key which point we are looking for.
   * @return Value from 0.0 to 1.0.
   */
  private static double getPointForKey(String key) {
    String hash = Hashing.sha384().hashString(key, StandardCharsets.UTF_8).toString();
    Long position = Long.valueOf(hash.substring(0, 1), 16);
    Long byteValue =
        Long.valueOf(hash.substring(position.intValue() * 2, position.intValue() * 2 + 2), 16);
    double result = byteValue / 255.0;
    LOG.debug("Point for key '%s' is: %f", key, result);
    return result;
  }

  private static String getKey(String name) {
    String username = System.getProperty("user.name");
    if (username == null) {
      username = "unknown";
    }
    String hostname = HOSTNAME_SUPPLIER.get();
    String result = username + "@" + hostname + "/" + name;
    LOG.debug("Determined key: '%s'", result);
    return result;
  }
}
