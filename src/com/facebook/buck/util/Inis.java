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

package com.facebook.buck.util;

import com.google.common.collect.ImmutableMap;

import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.IOException;
import java.io.Reader;

public class Inis {

  private Inis() {}

  public static ImmutableMap<String, ImmutableMap<String, String>> read(Reader reader)
      throws IOException {
    Ini ini = new Ini();
    ini.load(reader);
    validateIni(ini);

    ImmutableMap.Builder<String, ImmutableMap<String, String>> sectionsToEntries =
        ImmutableMap.builder();
    for (String sectionName : ini.keySet()) {
      ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
      Profile.Section section = ini.get(sectionName);
      for (String propertyName : section.keySet()) {
        String propertyValue = section.get(propertyName);
        builder.put(propertyName, propertyValue);
      }

      ImmutableMap<String, String> sectionToEntries = builder.build();
      sectionsToEntries.put(sectionName, sectionToEntries);
    }

    return sectionsToEntries.build();

  }

  private static void validateIni(Ini ini) throws IOException {
    // Verify that no section has the same key specified more than once.
    for (String sectionName : ini.keySet()) {
      Profile.Section section = ini.get(sectionName);
      for (String propertyName : section.keySet()) {
        if (section.getAll(propertyName).size() > 1) {
          throw new HumanReadableException(
              "Duplicate definition for %s in the [%s] section of your .buckconfig or " +
                  ".buckconfig.local.",
              propertyName,
              sectionName);
        }
      }
    }
  }

}
