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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class QuickstartCommandTest {

  @Test
  public void testexpandTildeInternal() {
    Iterable<String> homeDirs = ImmutableList.of("/Users/example", "/home/example");
    for (String homeDir : homeDirs) {
      assertEquals("expandTildeInternal should ignore tildes within a path",
          QuickstartCommand.expandTildeInternal(homeDir, "/~/abcdefgh"),
          "/~/abcdefgh");

      assertEquals("expandTildeInternal should expand a lone tilde (\"~\")",
          QuickstartCommand.expandTildeInternal(homeDir, "~"),
          homeDir);

      assertEquals("expandTildeInternal should not expand other users' home directories",
          QuickstartCommand.expandTildeInternal(homeDir, "~foobar/abcd"),
          "~foobar/abcd");

      assertEquals("expandTildeInternal should expand a tilde before a slash",
          QuickstartCommand.expandTildeInternal(homeDir, "~/another/folder"),
          homeDir + "/another/folder");
    }
  }
}
