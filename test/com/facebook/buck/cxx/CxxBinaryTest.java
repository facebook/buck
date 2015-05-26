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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxBinaryTest {

  @Test
  public void getExecutableCommandUsesAbsolutePath() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path bin = Paths.get("path/to/exectuable");
    filesystem.touch(bin);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//:target")
        .build();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    CxxBinary binary = new CxxBinary(
        params,
        ruleResolver,
        new SourcePathResolver(ruleResolver),
        bin,
        EasyMock.createMock(CxxLink.class),
        ImmutableList.<Path>of(),
        ImmutableList.<BuildTarget>of());
    ImmutableList<String> command = binary.getExecutableCommand(filesystem);
    assertTrue(Paths.get(command.get(0)).isAbsolute());
  }

}
