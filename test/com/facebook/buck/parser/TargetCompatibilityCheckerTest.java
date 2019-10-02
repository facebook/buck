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
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.impl.DefaultPlatform;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.config.registry.ImmutableConfigurationRuleRegistry;
import com.facebook.buck.core.rules.configsetting.ConfigSettingRule;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.rules.platform.ConstraintSettingRule;
import com.facebook.buck.core.rules.platform.ConstraintValueRule;
import com.facebook.buck.core.rules.platform.RuleBasedConstraintResolver;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.ConstructorArgBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Test;

public class TargetCompatibilityCheckerTest {

  private final BuildTarget cs1target =
      ConfigurationBuildTargetFactoryForTests.newInstance("//cs:cs1");
  private final ConstraintSetting cs1 = ConstraintSetting.of(cs1target, Optional.empty());
  private final BuildTarget cs1v1target =
      ConfigurationBuildTargetFactoryForTests.newInstance("//cs:cs1v1");
  private final ConstraintValue cs1v1 = ConstraintValue.of(cs1v1target, cs1);
  private final BuildTarget cs1v2target =
      ConfigurationBuildTargetFactoryForTests.newInstance("//cs:cs1v2");
  private final ConstraintValue cs1v2 = ConstraintValue.of(cs1v2target, cs1);

  private Platform platform;
  private ConfigurationRuleRegistry configurationRuleRegistry;
  private ConfigSettingRule compatibleConfigSetting;
  private ConfigSettingRule nonCompatibleConfigSetting;

  @Before
  public void setUp() {
    platform =
        new ConstraintBasedPlatform(
            BuildTargetFactory.newInstance("//platform:platform"), ImmutableSet.of(cs1v1));
    ConstraintResolver constraintResolver =
        new RuleBasedConstraintResolver(
            buildTarget -> {
              if (buildTarget.equals(cs1.getBuildTarget())) {
                return new ConstraintSettingRule(
                    buildTarget, buildTarget.getShortName(), Optional.empty());
              } else {
                return new ConstraintValueRule(
                    buildTarget, buildTarget.getShortName(), cs1.getBuildTarget());
              }
            });
    compatibleConfigSetting =
        new ConfigSettingRule(
            BuildTargetFactory.newInstance("//configs:c1"),
            ImmutableMap.of(),
            ImmutableSet.of(cs1v1.getBuildTarget()));
    nonCompatibleConfigSetting =
        new ConfigSettingRule(
            BuildTargetFactory.newInstance("//configs:c2"),
            ImmutableMap.of(),
            ImmutableSet.of(cs1v2.getBuildTarget()));
    ConfigurationRuleResolver configurationRuleResolver =
        buildTarget -> {
          if (buildTarget.toString().equals(compatibleConfigSetting.getBuildTarget().toString())) {
            return compatibleConfigSetting;
          }
          if (buildTarget
              .toString()
              .equals(nonCompatibleConfigSetting.getBuildTarget().toString())) {
            return nonCompatibleConfigSetting;
          }
          throw new RuntimeException("Unknown configuration rule: " + buildTarget);
        };
    configurationRuleRegistry =
        new ImmutableConfigurationRuleRegistry(
            configurationRuleResolver,
            constraintResolver,
            configuration -> DefaultPlatform.INSTANCE);
  }

  @Test
  public void testTargetNodeIsCompatibleWithEmptyConstraintList() throws Exception {
    ConstructorArg targetNodeArg = createTargetNodeArg(ImmutableMap.of());
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsCompatibleWithMatchingConstraintList() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "targetCompatibleWith",
                ImmutableList.of(cs1v1.getBuildTarget().getFullyQualifiedName())));
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingConstraintList() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "targetCompatibleWith",
                ImmutableList.of(cs1v2.getBuildTarget().getFullyQualifiedName())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingPlatformAndNonMatchingConstraintList()
      throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatibleWith",
                ImmutableList.of(nonCompatibleConfigSetting.getBuildTarget().toString()),
                "targetCompatibleWith",
                ImmutableList.of(cs1v2.getBuildTarget().getFullyQualifiedName())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingPlatformListAndMatchingConstraintList()
      throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatibleWith",
                ImmutableList.of(nonCompatibleConfigSetting.getBuildTarget().toString()),
                "targetCompatibleWith",
                ImmutableList.of(cs1v1.getBuildTarget().getFullyQualifiedName())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithMatchingPlatformListAndNonMatchingConstraintList()
      throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatibleWith",
                ImmutableList.of(compatibleConfigSetting.getBuildTarget().toString()),
                "targetCompatibleWith",
                ImmutableList.of(cs1v2.getBuildTarget().getFullyQualifiedName())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingPlatformList() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatibleWith",
                ImmutableList.of(nonCompatibleConfigSetting.getBuildTarget().toString())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsCompatibleWithMatchingPlatformList() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatibleWith",
                ImmutableList.of(
                    compatibleConfigSetting.getBuildTarget().toString(),
                    nonCompatibleConfigSetting.getBuildTarget().toString())));
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  @Test
  public void testTargetNodeIsCompatibleWithMatchingPlatformListAndMatchingConstraintList()
      throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatibleWith",
                ImmutableList.of(compatibleConfigSetting.getBuildTarget().toString()),
                "targetCompatibleWith",
                ImmutableList.of(cs1v1.getBuildTarget().getFullyQualifiedName())));
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry, targetNodeArg, platform));
  }

  private ConstructorArg createTargetNodeArg(Map<String, Object> rawNode) throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller marshaller = new DefaultConstructorArgMarshaller(typeCoercerFactory);
    KnownNativeRuleTypes knownRuleTypes =
        KnownNativeRuleTypes.of(ImmutableList.of(new TestRuleDescription()), ImmutableList.of());

    BuildTarget buildTarget = BuildTargetFactory.newInstance(projectFilesystem, "//:target");

    ConstructorArgBuilder<TestDescriptionArg> builder =
        knownRuleTypes.getConstructorArgBuilder(
            typeCoercerFactory,
            knownRuleTypes.getRuleType("test_rule"),
            TestDescriptionArg.class,
            buildTarget);

    return marshaller.populate(
        TestCellPathResolver.get(projectFilesystem),
        projectFilesystem,
        buildTarget,
        builder,
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>builder().putAll(rawNode).put("name", "target").build());
  }

  static class TestRuleDescription implements RuleDescription<AbstractTestDescriptionArg> {

    @Override
    public boolean producesCacheableSubgraph() {
      return false;
    }

    @Override
    public ProviderInfoCollection ruleImpl(
        RuleAnalysisContext context, BuildTarget target, AbstractTestDescriptionArg args)
        throws ActionCreationException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Class<AbstractTestDescriptionArg> getConstructorArgType() {
      return AbstractTestDescriptionArg.class;
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractTestDescriptionArg extends CommonDescriptionArg {}
}
