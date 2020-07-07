/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.downwardapi.utils;

/** Constants related to Downward API. */
public class DownwardApiConstants {
  public static final String ENV_VERBOSITY = "BUCK_VERBOSITY";

  public static final String ENV_ANSI_ENABLED = "BUCK_ANSI_ENABLED";

  public static final String ENV_BUILD_UUID = "BUCK_BUILD_UUID";

  public static final String ENV_ACTION_ID = "BUCK_ACTION_ID";

  public static final String ENV_EVENT_PIPE = "BUCK_EVENT_PIPE";
}
