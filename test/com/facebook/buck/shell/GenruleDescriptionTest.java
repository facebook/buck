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

package com.facebook.buck.shell;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.Map;

public class GenruleDescriptionTest {

  @Test
  public void testImplicitDepsAreAddedCorrectly() throws NoSuchBuildTargetException {
    Description<GenruleDescription.Arg> genruleDescription = new GenruleDescription();
    Map<String, Object> instance = ImmutableMap.<String, Object>of(
        "srcs", ImmutableList.of(":baz", "//biz:baz"),
        "out", "AndroidManifest.xml",
        "cmd", "$(exe //bin:executable) $(location :arg)");
    ProjectFilesystem projectFilesystem = new AllExistingProjectFilesystem();
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        instance,
        projectFilesystem,
        new BuildTargetParser(),
        BuildTargetFactory.newInstance("//foo:bar"),
        new FakeRuleKeyBuilderFactory());
    TargetNode<GenruleDescription.Arg> targetNode = new TargetNode<>(genruleDescription, params);
    assertEquals(
        "SourcePaths and targets from cmd string should be extracted as extra deps.",
        ImmutableSet.of(
            "//foo:baz",
            "//biz:baz",
            "//bin:executable",
            "//foo:arg"),
        FluentIterable.from(targetNode.getExtraDeps())
            .transform(Functions.toStringFunction())
            .toSet());
  }
}
