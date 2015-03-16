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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FlavorTest {

  @Test
  public void replaceInvalidCharacters() {
    assertEquals(
        "abcd",
        Flavor.replaceInvalidCharacters("abcd"));
    assertEquals(
        "abcd_efgh_ijkl",
        Flavor.replaceInvalidCharacters("abcd/efgh/ijkl"));
    assertEquals(
        "abcd_ABCD_e_fg_h-i.jkl.mn_opq____r___049",
        Flavor.replaceInvalidCharacters("abcd/ABCD/e_fg+h-i.jkl.mn/opq@#$$r/()049"));
  }

}
