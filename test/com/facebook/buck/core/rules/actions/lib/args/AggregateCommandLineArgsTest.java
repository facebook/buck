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
import org.junit.Test;

public class AggregateCommandLineArgsTest {

  @Test
  public void returnsProperStreamAndArgCount() {
    CommandLineArgs args =
        new AggregateCommandLineArgs(
            ImmutableList.of(
                CommandLineArgsFactory.from(ImmutableList.of(1)),
                CommandLineArgsFactory.from(ImmutableList.of("foo", "bar"))));

    assertEquals(
        ImmutableList.of("1", "foo", "bar"),
        args.getStrings(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .collect(ImmutableList.toImmutableList()));
    assertEquals(3, args.getEstimatedArgsCount());
  }
}
