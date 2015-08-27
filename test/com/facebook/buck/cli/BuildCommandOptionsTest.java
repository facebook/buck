/*
 * Copyright 2012-present Facebook, Inc.
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
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

public class BuildCommandOptionsTest {

  @Test
  public void testCreateJavaPackageFinder() {
    BuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.of(
            "java",
            ImmutableMap.of("src_roots", "src, test")));
    DefaultJavaPackageFinder javaPackageFinder = buckConfig.createDefaultJavaPackageFinder();
    assertEquals(ImmutableSortedSet.of(), javaPackageFinder.getPathsFromRoot());
    assertEquals(ImmutableSet.of("src", "test"), javaPackageFinder.getPathElements());
  }

  @Test
  public void testCreateJavaPackageFinderFromEmptyBuckConfig() {
    BuckConfig buckConfig = new FakeBuckConfig();
    DefaultJavaPackageFinder javaPackageFinder = buckConfig.createDefaultJavaPackageFinder();
    assertEquals(ImmutableSortedSet.<String>of(), javaPackageFinder.getPathsFromRoot());
    assertEquals(ImmutableSet.of(), javaPackageFinder.getPathsFromRoot());
  }

  @Test
  public void testCommandLineOptionOverridesOtherBuildThreadSettings() throws CmdLineException {
    BuildCommand command = new BuildCommand();

    AdditionalOptionsCmdLineParser parser = new AdditionalOptionsCmdLineParser(command);
    parser.parseArgument("--num-threads", "42");

    BuckConfig buckConfig = new FakeBuckConfig(command.getConfigOverrides());
    assertThat(buckConfig.getNumThreads(), Matchers.equalTo(42));
  }
}
