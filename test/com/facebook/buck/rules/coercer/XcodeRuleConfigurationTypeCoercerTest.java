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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class XcodeRuleConfigurationTypeCoercerTest {

  BuildTargetParser buildTargetParser;
  FakeProjectFilesystem filesystem;
  Path basePath;
  TypeCoercer<XcodeRuleConfiguration> coercer;

  @Before
  public void setUp() {
    buildTargetParser = new BuildTargetParser();
    filesystem = new FakeProjectFilesystem();
    basePath = Paths.get("base");
    TypeCoercer<String> stringTypeCoercer = new IdentityTypeCoercer<>(String.class);
    TypeCoercer<BuildTarget> buildTargetTypeCoercer = new BuildTargetTypeCoercer();
    TypeCoercer<Path> pathTypeCoercer = new PathTypeCoercer();
    TypeCoercer<SourcePath> sourcePathTypeCoercer = new SourcePathTypeCoercer(
        buildTargetTypeCoercer,
        pathTypeCoercer);
    TypeCoercer<XcodeRuleConfigurationLayer> layerTypeCoercer =
        new XcodeRuleConfigurationLayerTypeCoercer(
            sourcePathTypeCoercer,
            new MapTypeCoercer<>(stringTypeCoercer, stringTypeCoercer));
    coercer = new XcodeRuleConfigurationTypeCoercer(layerTypeCoercer);
  }

  @Test
  public void coercingAListOfMaps() throws NoSuchFieldException, CoerceFailedException {
    XcodeRuleConfiguration configuration = coercer.coerce(
        buildTargetParser,
        filesystem,
        basePath,
        ImmutableList.of(ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of()));

    assertEquals(
        configuration,
        new XcodeRuleConfiguration(
            ImmutableList.of(
                new XcodeRuleConfigurationLayer(ImmutableMap.<String, String>of()),
                new XcodeRuleConfigurationLayer(ImmutableMap.<String, String>of()),
                new XcodeRuleConfigurationLayer(ImmutableMap.<String, String>of()))));
  }

  @Test
  public void coercingAMap() throws NoSuchFieldException, CoerceFailedException {
    XcodeRuleConfiguration configuration = coercer.coerce(
        buildTargetParser,
        filesystem,
        basePath,
        ImmutableMap.of());

    assertEquals(
        configuration,
        new XcodeRuleConfiguration(
            ImmutableList.of(
                new XcodeRuleConfigurationLayer(ImmutableMap.<String, String>of()))));
  }
}
