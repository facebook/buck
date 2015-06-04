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

package com.facebook.buck.apple.simulator;

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.equalTo;

import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Unit tests for {@link SimctlListOutputParsing}.
 */
public class SimctlListOutputParsingTest {
  @Test
  public void simctlListOutputParsesToAppleSimulators() throws IOException {
    ImmutableSet.Builder<AppleSimulator> simulatorsBuilder = ImmutableSet.builder();

    try (InputStream in = getClass().getResourceAsStream("testdata/simctl-list.txt");
         InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      SimctlListOutputParsing.parseOutputFromReader(reader, simulatorsBuilder);
    }

    ImmutableSet<AppleSimulator> expected = ImmutableSet.<AppleSimulator>builder()
        .add(AppleSimulator.builder()
                .setName("iPhone 4s")
                .setUdid("F7C1CC9A-945E-4258-BA84-DEEBE683798B")
                .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                .build())
        .add(AppleSimulator.builder()
                 .setName("iPhone 5")
                 .setUdid("45BD7164-686C-474F-8C68-3730432BC5F2")
                 .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("iPhone 5s")
                 .setUdid("70200ED8-EEF1-4BDB-BCCF-3595B137D67D")
                 .setSimulatorState(AppleSimulatorState.BOOTED)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("iPhone 6 Plus")
                 .setUdid("92340ACF-2C44-455F-BACD-573B133FB20E")
                 .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("iPhone 6")
                 .setUdid("A75FF972-FE12-4656-A8CC-99572879D4A3")
                 .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("iPad 2")
                 .setUdid("CC1B0BAD-BAE6-4A53-92CF-F79850654057")
                 .setSimulatorState(AppleSimulatorState.SHUTTING_DOWN)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("iPad Retina")
                 .setUdid("137AAA25-54A1-42E8-8202-84DEADD668E1")
                 .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("iPad Air")
                 .setUdid("554B2E0F-63F3-4400-8319-5C5062CF4C95")
                 .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("Resizable iPhone")
                 .setUdid("58E3748F-F7E6-4A45-B52C-A136B59F7A42")
                 .setSimulatorState(AppleSimulatorState.CREATING)
                 .build())
        .add(AppleSimulator.builder()
                 .setName("Resizable iPad")
                 .setUdid("56FE1CBC-61FF-443D-8E23-19D05864C6DB")
                 .setSimulatorState(AppleSimulatorState.SHUTDOWN)
                 .build())
        .build();

    assertThat(simulatorsBuilder.build(), is(equalTo(expected)));
  }
}
