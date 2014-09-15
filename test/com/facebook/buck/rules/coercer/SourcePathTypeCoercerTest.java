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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcePathTypeCoercerTest {

  @Test
  public void coercingSourcePathsSetsNames()
      throws NoSuchFieldException, CoerceFailedException {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    PathTypeCoercer pathTypeCoercer = new PathTypeCoercer();
    BuildTargetTypeCoercer buildTargetTypeCoercer = new BuildTargetTypeCoercer();
    SourcePathTypeCoercer sourcePathTypeCoercer =
        new SourcePathTypeCoercer(buildTargetTypeCoercer, pathTypeCoercer);

    Path basePath = Paths.get("base");

    // Verify that regular strings coerced as PathSourcePaths preserve their original name.
    String src = "test/source.cpp";
    SourcePath res = sourcePathTypeCoercer.coerce(
        new BuildTargetParser(),
        buildRuleResolver,
        filesystem,
        basePath,
        src);
    assertEquals(res.getName(), src);
  }

}
