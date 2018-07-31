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

package com.facebook.buck.util.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import org.ini4j.Config;
import org.ini4j.Ini;
import org.ini4j.Profile;

class Inis {

  private Inis() {}

  // The input to this method is passed in as a URL object in order to make
  // include handling in the ini files sane.  Ini4j uses URL's base path
  // in order to construct the path for the included files.
  // So, if the ini file includes e.g. <file:../relative_file_path>, then the full
  // path will be constructed based on the base (directory) path of the passed in
  // config; and if the include is an absolute path, it will also be handled correctly.
  public static ImmutableMap<String, ImmutableMap<String, String>> read(URL config)
      throws IOException {
    Ini ini = makeIniParser(/*enable_includes=*/ true);
    ini.load(config);
    return toMap(ini);
  }

  // This method should be used by tests only in order to construct an in-memory
  // buck config.  The includes are not enabled in this case (since include
  // location, particularly relative includes, is not well defined).
  @VisibleForTesting
  static ImmutableMap<String, ImmutableMap<String, String>> read(Reader reader) throws IOException {
    Ini ini = makeIniParser(/*enable_includes=*/ false);
    ini.load(reader);
    return toMap(ini);
  }

  // Creates and configures ini parser.
  private static Ini makeIniParser(boolean enable_includes) {
    Ini ini = new Ini();
    Config config = ini.getConfig();
    config.setEscape(false);
    config.setEscapeNewline(true);
    config.setMultiOption(false);
    config.setInclude(enable_includes);
    return ini;
  }

  // Converts specified (loaded) ini config to an immutable map.
  private static ImmutableMap<String, ImmutableMap<String, String>> toMap(Ini ini) {
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
}
