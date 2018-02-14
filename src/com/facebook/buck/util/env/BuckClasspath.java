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

package com.facebook.buck.util.env;

import javax.annotation.Nullable;

/** Current process classpath. Set by buckd or by launcher script. */
public class BuckClasspath {

  public static final String ENV_VAR_NAME = "BUCK_CLASSPATH";

  public static String getBuckClasspathFromEnvVarOrThrow() {
    String envVarValue = System.getenv(ENV_VAR_NAME);
    if (envVarValue == null) {
      throw new IllegalStateException(ENV_VAR_NAME + " env var is not set");
    }
    if (envVarValue.isEmpty()) {
      throw new IllegalStateException(ENV_VAR_NAME + " env var is set to empty string");
    }
    return envVarValue;
  }

  @Nullable
  public static String getBuckClasspathFromEnvVarOrNull() {
    return System.getenv(ENV_VAR_NAME);
  }
}
