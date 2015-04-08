/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.facebook.buck.log.Logger;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Parses a string containing one or more flavor names.
 */
public class FlavorParser {
  private static final Logger LOG = Logger.get(FlavorParser.class);
  private static final ImmutableMap<String, String> DEPRECATED_FLAVORS =
      ImmutableMap.of("dynamic", "shared");

  private final Set<String> deprecatedFlavorWarningShown = new ConcurrentSkipListSet<>();

  /**
   * Given a comma-separated string of flavors, returns an iterable
   * containing the separated names of the flavors inside.
   *
   * Also maps deprecated flavor names to their supported names.
   */
  public Iterable<String> parseFlavorString(String flavorString) {
    return
        Iterables.transform(
            Splitter.on(",")
                .omitEmptyStrings()
                .trimResults()
                .split(flavorString),
            new Function<String, String>() {
                @Override
                public String apply(String flavor) {
                  String mapped = DEPRECATED_FLAVORS.get(flavor);
                  if (mapped != null) {
                    // Show a warning the first time a deprecated flavor is used.
                    if (deprecatedFlavorWarningShown.add(flavor)) {
                      LOG.warn("Flavor %s is deprecated; use %s instead.", flavor, mapped);
                    }
                    return mapped;
                  } else {
                    return flavor;
                  }
                }
            });
  }
}
