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

package com.facebook.buck.thrift;

import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class JavaThriftLibraryDescriptionTest {
  @Test
  public void thriftLibraryDepConfig() {
    Map<String, Map<String, String>> sections = new HashMap<>();
    Map<String, String> javaSection = new HashMap<>();
    javaSection.put("thrift_library", "//thrift:lib");
    sections.put("java", javaSection);
    FakeBuckConfig buckConfig = new FakeBuckConfig(sections);
    BuildRuleFactoryParams buildRuleFactoryParams = new BuildRuleFactoryParams(
        new HashMap<String, Object>(),
        new FakeProjectFilesystem(),
        createMock(BuildTargetParser.class),
        BuildTarget.builder("//test", "test").build(),
        createMock(RuleKeyBuilderFactory.class));
    JavaThriftLibraryDescription description = new JavaThriftLibraryDescription(
        JavaCompilerEnvironment.DEFAULT,
        new JavaBuckConfig(buckConfig));
    assertEquals("Configured java thrift library is not a dep",
        ImmutableSet.of("//thrift:lib"),
        description.findDepsFromParams(buildRuleFactoryParams));
  }
}
