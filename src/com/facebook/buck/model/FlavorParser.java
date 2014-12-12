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

import com.google.common.base.Splitter;

/**
 * Parses a string containing one or more flavor names.
 */
public class FlavorParser {
  /**
   * Given a comma-separated string of flavors, returns an iterable
   * containing the separated names of the flavors inside.
   */
  public Iterable<String> parseFlavorString(String flavorString) {
    return
        Splitter.on(",")
            .omitEmptyStrings()
            .trimResults()
            .split(flavorString);
  }
}
