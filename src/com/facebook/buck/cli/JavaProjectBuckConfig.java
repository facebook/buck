/*
 * Copyright 2013-present Facebook, Inc.
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

import com.google.common.base.Optional;

import java.util.HashMap;

public class JavaProjectBuckConfig {

  private static final String JDK_NAME_TOKEN = "jdk_name";
  private static final String JDK_TYPE_TOKEN = "jdk_type";
  private static final String JAVA_LANGUAGE_LEVEL_TOKEN = "language_level";

  private final HashMap<String, Optional<String>> values;

  public JavaProjectBuckConfig(HashMap<String, Optional<String>> values) {
    this.values = values;
  }

  public static JavaProjectBuckConfig emptyJavaConfig() {
    HashMap<String, Optional<String>> values = new HashMap<String, Optional<String>>();
    for (String token : getJavaConfigTokens()) {
      values.put(token, Optional.<String>absent());
    }
    return new JavaProjectBuckConfig(values);
  }

  public static String[] getJavaConfigTokens() {
    String[] configTokens = {
        JDK_NAME_TOKEN,
        JDK_TYPE_TOKEN,
        JAVA_LANGUAGE_LEVEL_TOKEN,
    };
    return configTokens;
  }

  public String resolveJDKName() {
    return values.get(JDK_NAME_TOKEN).orNull();
  }

  public String resolveJDKType() {
    return values.get(JDK_TYPE_TOKEN).orNull();
  }

  public String resolveLanguageLevel() {
    return values.get(JAVA_LANGUAGE_LEVEL_TOKEN).orNull();
  }
}