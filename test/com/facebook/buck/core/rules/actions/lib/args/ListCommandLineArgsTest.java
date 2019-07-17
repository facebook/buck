/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.actions.lib.args;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ListCommandLineArgsTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void returnsProperStreamAndSize() {
    CommandLineArgs args = new ListCommandLineArgs(ImmutableList.of(1, "foo"));
    assertEquals(
        ImmutableList.of("1", "foo"),
        args.getStrings(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .collect(ImmutableList.toImmutableList()));
    assertEquals(2, args.getEstimatedArgsCount());
  }
}
