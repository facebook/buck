/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;

@SuppressWarnings("PMD.AddEmptyString")
public class ExopackageInstallerTest {
  @Test
  public void testScanSecondaryDexDir() throws Exception {
    String output =
        "exopackage_temp-secondary-abcdefg.dex.jar-588103794.tmp\r\n" +
        "lock\r\n" +
        "metadata.txt\r\n" +
        "secondary-0fa1f9cfb3c0effa8000d2d86d267985b158df9f.dex.jar\r\n" +
        "secondary-07fc80d2de21bd1dd57be0728fdb6c14190c3386.dex.jar\r\n" +
        "secondary-2add18058985241f7999eb026868cebb9ef63379.dex.jar\r\n" +
        "";
    ImmutableSet<String> requiredHashes = ImmutableSet.of(
        "0fa1f9cfb3c0effa8000d2d86d267985b158df9f",
        "2add18058985241f7999eb026868cebb9ef63379",
        "97d21318d1d5dd298f6ee932916c6ee949fe760e");
    ImmutableSet.Builder<String> foundHashesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> toDeleteBuilder = ImmutableSet.builder();

    ExopackageInstaller.scanSecondaryDexDir(
        output,
        requiredHashes,
        foundHashesBuilder,
        toDeleteBuilder);

    assertEquals(
        ImmutableSet.of(
            "0fa1f9cfb3c0effa8000d2d86d267985b158df9f",
            "2add18058985241f7999eb026868cebb9ef63379"),
        foundHashesBuilder.build()
    );

    assertEquals(
        ImmutableSet.of(
            "exopackage_temp-secondary-abcdefg.dex.jar-588103794.tmp",
            "metadata.txt",
            "secondary-07fc80d2de21bd1dd57be0728fdb6c14190c3386.dex.jar"),
        toDeleteBuilder.build());
  }
}
