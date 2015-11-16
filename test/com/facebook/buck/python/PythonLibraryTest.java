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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unit test for {@link PythonLibrary}.
 */
public class PythonLibraryTest {
  @Rule
  public final TemporaryFolder projectRootDir = new TemporaryFolder();

  @Test
  public void testGetters() {
    ImmutableMap<Path, SourcePath> srcs = ImmutableMap.<Path, SourcePath>of(
        Paths.get("dummy"), new FakeSourcePath(""));
    PythonLibrary pythonLibrary = new PythonLibrary(
        new FakeBuildRuleParamsBuilder(
            BuildTargetFactory.newInstance("//scripts/python:foo"))
            .build(),
        new SourcePathResolver(new BuildRuleResolver()),
        Functions.constant(srcs),
        Functions.constant(ImmutableMap.<Path, SourcePath>of()),
        Optional.<Boolean>absent());

    assertTrue(pythonLibrary.getProperties().is(LIBRARY));
  }

}
