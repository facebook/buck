/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AbstractLocationMacroTest {
  @Test
  public void testSplitParsableBuildTarget() {
    assertThat(
        AbstractLocationMacro.splitSupplementaryOutputPart("target[sup]").target,
        Matchers.equalTo("target"));
    assertThat(
        AbstractLocationMacro.splitSupplementaryOutputPart("target[sup]").supplementaryOutput,
        Matchers.equalTo(Optional.of("sup")));
    assertThat(
        AbstractLocationMacro.splitSupplementaryOutputPart("target").target,
        Matchers.equalTo("target"));
    assertThat(
        AbstractLocationMacro.splitSupplementaryOutputPart("target").supplementaryOutput,
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void testSplitUnparsableBuildTarget() {
    try {
      AbstractLocationMacro.splitSupplementaryOutputPart("");
      fail("Expected a HumanReadableException");
    } catch (HumanReadableException e) {
      assertThat(
          e.getMessage(),
          Matchers.startsWith("Cannot parse build target"));
    }
  }
}
