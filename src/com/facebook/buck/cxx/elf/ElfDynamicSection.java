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

package com.facebook.buck.cxx.elf;

public class ElfDynamicSection {

  private ElfDynamicSection() {}

  public enum DTag {
    DT_NULL(0),
    DT_NEEDED(1),
    DT_PLTGOT(3),
    DT_INIT(12),
    DT_FINI(13),
    DT_SONAME(14),
    DT_UNKNOWN(0xffffffff),
    ;

    private final int value;

    DTag(int value) {
      this.value = value;
    }

    public static DTag valueOf(int val) {
      for (DTag clazz : DTag.values()) {
        if (clazz.value == val) {
          return clazz;
        }
      }
      return DT_UNKNOWN;
    }
  }
}
