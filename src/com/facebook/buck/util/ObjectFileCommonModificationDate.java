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

package com.facebook.buck.util;

public class ObjectFileCommonModificationDate {
  private ObjectFileCommonModificationDate() {}

  /**
   * 01 Feb 1985 00:00:00 (12:00:00 AM). This value should be in sync with ZipConstants to make sure
   * that cached files have the same time stamp as the files that have been built locally.
   */
  public static final int COMMON_MODIFICATION_TIME_STAMP = 476064000;
}
