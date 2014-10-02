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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.util.Map;

public class CxxTestDescriptionTest {

  @Test
  public void findDepsFromParams() {
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTargetParser parser = new BuildTargetParser();

    BuildTarget gtest = BuildTargetFactory.newInstance("//:gtest");

    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx",
            ImmutableMap.of("gtest_dep", gtest.toString())));
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    CxxTestDescription desc = new CxxTestDescription(cxxBuckConfig);

    // Test the default test type is gtest.
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        ImmutableMap.<String, Object>of(),
        filesystem,
        parser,
        target,
        new FakeRuleKeyBuilderFactory());
    Iterable<String> implicit = desc.findDepsFromParams(params);
    assertTrue(Iterables.contains(implicit, gtest.toString()));

    // Test explicitly setting gtest works as well.
    params = new BuildRuleFactoryParams(
        ImmutableMap.<String, Object>of("framework", CxxTestType.GTEST.toString()),
        filesystem,
        parser,
        target,
        new FakeRuleKeyBuilderFactory());
    implicit = desc.findDepsFromParams(params);
    assertTrue(Iterables.contains(implicit, gtest.toString()));
  }

}
