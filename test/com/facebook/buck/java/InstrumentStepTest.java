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

package com.facebook.buck.java;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class InstrumentStepTest {

  @Test
  public void testGetShellCommandInternal() {
    String mode = "overwrite";
    Set<Path> instrumentDirectories = ImmutableSet.of(
        Paths.get("root/directory1"), Paths.get("directory2"));

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    List<String> expectedShellCommand = ImmutableList.of(
        "java",
        "-classpath", JUnitStep.PATH_TO_EMMA_JAR.toString(),
        "emma", "instr",
        "-outmode", mode,
        "-outfile", String.format("%s/coverage.em", JUnitStep.EMMA_OUTPUT_DIR),
        "-instrpath", "root/directory1,directory2");

    InstrumentStep command = new InstrumentStep(mode, instrumentDirectories);

    MoreAsserts.assertListEquals(expectedShellCommand,
        command.getShellCommand(context));

    verify(context);
  }
}
