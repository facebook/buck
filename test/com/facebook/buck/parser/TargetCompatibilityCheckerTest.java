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
package com.facebook.buck.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.platform.ConstraintSettingRule;
import com.facebook.buck.core.rules.platform.ConstraintValueRule;
import com.facebook.buck.core.rules.platform.RuleBasedConstraintResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Test;

public class TargetCompatibilityCheckerTest {

  private final ConstraintSetting cs1 =
      ConstraintSetting.of(UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1"));
  private final ConstraintValue cs1v1 =
      ConstraintValue.of(UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1v1"), cs1);
  private final ConstraintValue cs1v2 =
      ConstraintValue.of(UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1v2"), cs1);

  private Platform platform;
  private ConstraintResolver constraintResolver;

  @Before
  public void setUp() throws Exception {
    platform = new ConstraintBasedPlatform("", ImmutableSet.of(cs1v1));
    constraintResolver =
        new RuleBasedConstraintResolver(
            buildTarget -> {
              if (buildTarget.equals(cs1.getBuildTarget())) {
                return new ConstraintSettingRule(buildTarget, buildTarget.getShortName());
              } else {
                return new ConstraintValueRule(
                    buildTarget, buildTarget.getShortName(), cs1.getBuildTarget());
              }
            });
  }

  @Test
  public void testTargetNodeIsCompatibleWithEmptyConstraintList() throws Exception {
    Object targetNodeArg = createTargetNodeArg(ImmutableMap.of());
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            constraintResolver, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsCompatibleWithMatchingConstraintList() throws Exception {
    Object targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "targetCompatibleWith",
                ImmutableList.of(cs1v1.getBuildTarget().getFullyQualifiedName())));
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            constraintResolver, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingConstraintList() throws Exception {
    Object targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "targetCompatibleWith",
                ImmutableList.of(cs1v2.getBuildTarget().getFullyQualifiedName())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            constraintResolver, targetNodeArg, platform));
  }

  private Object createTargetNodeArg(Map<String, Object> rawNode) throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ConstructorArgMarshaller marshaller =
        new DefaultConstructorArgMarshaller(new DefaultTypeCoercerFactory());
    return marshaller.populate(
        TestCellPathResolver.get(projectFilesystem),
        projectFilesystem,
        BuildTargetFactory.newInstance(projectFilesystem, "//:target"),
        TestDescriptionArg.class,
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>builder().putAll(rawNode).put("name", "target").build());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractTestDescriptionArg extends CommonDescriptionArg {}
}
