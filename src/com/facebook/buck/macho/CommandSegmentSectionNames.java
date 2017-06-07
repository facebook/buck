/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.macho;

public class CommandSegmentSectionNames {
  private CommandSegmentSectionNames() {}

  public static final String SEGMENT_NAME_DWARF = "__DWARF";
  public static final String SECTION_NAME_DEBUG_STR = "__debug_str";
  public static final String SEGMENT_LINKEDIT = "__LINKEDIT";
}
