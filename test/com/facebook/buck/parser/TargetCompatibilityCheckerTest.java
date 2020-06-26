/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.ThrowingTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.graph.ConfigurationGraphDependencyStack;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.configsetting.ConfigSettingRule;
import com.facebook.buck.core.rules.platform.ConstraintSettingRule;
import com.facebook.buck.core.rules.platform.ConstraintValueRule;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.select.BuckConfigKey;
import com.facebook.buck.core.select.LabelledAnySelectable;
import com.facebook.buck.core.select.SelectableConfigurationContextFactory;
import com.facebook.buck.core.select.impl.ThrowingSelectorListResolver;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class TargetCompatibilityCheckerTest {

  private final BuildTarget cs1target =
      ConfigurationBuildTargetFactoryForTests.newInstance("//cs:cs1");
  private final ConstraintSettingRule cs1r = new ConstraintSettingRule(cs1target);
  private final ConstraintSetting cs1 = cs1r.getConstraintSetting();
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
  private ConfigSettingRule compatibleConfigSettingWithValues;

  private final BuckConfig buckConfig = FakeBuckConfig.empty();

  @Before
  public void setUp() {
    platform =
        new ConstraintBasedPlatform(
            ConfigurationBuildTargetFactoryForTests.newInstance("//platform:platform"),
            ImmutableSet.of(cs1v1));
    compatibleConfigSetting =
        new ConfigSettingRule(
            ConfigurationBuildTargetFactoryForTests.newInstance("//configs:c1"),
            ImmutableMap.of(),
            ImmutableSet.of(new ConstraintValueRule(cs1v1.getBuildTarget(), cs1r)));
    nonCompatibleConfigSetting =
        new ConfigSettingRule(
            ConfigurationBuildTargetFactoryForTests.newInstance("//configs:c2"),
            ImmutableMap.of(),
            ImmutableSet.of(new ConstraintValueRule(cs1v2.getBuildTarget(), cs1r)));
    compatibleConfigSettingWithValues =
        new ConfigSettingRule(
            ConfigurationBuildTargetFactoryForTests.newInstance("//configs:c-values"),
            ImmutableMap.of(BuckConfigKey.parse("section.config"), "true"),
            ImmutableSet.of());
    ConfigurationRuleResolver configurationRuleResolver =
        new ConfigurationRuleResolver() {
          @Override
          public <R extends ConfigurationRule> R getRule(
              BuildTarget buildTarget,
              Class<R> ruleClass,
              ConfigurationGraphDependencyStack dependencyStack) {
            if (buildTarget
                .toString()
                .equals(compatibleConfigSetting.getBuildTarget().toString())) {
              return ruleClass.cast(compatibleConfigSetting);
            }
            if (buildTarget
                .toString()
                .equals(nonCompatibleConfigSetting.getBuildTarget().toString())) {
              return ruleClass.cast(nonCompatibleConfigSetting);
            }
            if (buildTarget
                .toString()
                .equals(compatibleConfigSettingWithValues.getBuildTarget().toString())) {
              return ruleClass.cast(compatibleConfigSettingWithValues);
            }
            throw new RuntimeException("Unknown configuration rule: " + buildTarget);
          }
        };
    configurationRuleRegistry =
        ConfigurationRuleRegistry.of(
            configurationRuleResolver,
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);
  }

  @Test
  public void testTargetNodeIsCompatibleWithEmptyConstraintList() throws Exception {
    ConstructorArg targetNodeArg = createTargetNodeArg(ImmutableMap.of());
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry,
            targetNodeArg,
            platform,
            DependencyStack.root(),
            buckConfig));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingPlatformList() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatible_with",
                ImmutableList.of(
                    nonCompatibleConfigSetting.getBuildTarget().getUnflavoredBuildTarget())));
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry,
            targetNodeArg,
            platform,
            DependencyStack.root(),
            buckConfig));
  }

  @Test
  public void testTargetNodeIsCompatibleWithMatchingPlatformList() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatible_with",
                ImmutableList.of(
                    compatibleConfigSetting.getBuildTarget().getUnflavoredBuildTarget(),
                    nonCompatibleConfigSetting.getBuildTarget().getUnflavoredBuildTarget())));
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry,
            targetNodeArg,
            platform,
            DependencyStack.root(),
            buckConfig));
  }

  @Test
  public void testTargetNodeIsCompatibleWithMatchingBuckConfigValues() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatible_with",
                ImmutableList.of(
                    compatibleConfigSettingWithValues
                        .getBuildTarget()
                        .getUnflavoredBuildTarget())));

    BuckConfig compatibleBuckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("section", ImmutableMap.of("config", "true")))
            .build();
    assertTrue(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry,
            targetNodeArg,
            platform,
            DependencyStack.root(),
            compatibleBuckConfig));
  }

  @Test
  public void testTargetNodeIsNotCompatibleWithNonMatchingBuckConfigValues() throws Exception {
    ConstructorArg targetNodeArg =
        createTargetNodeArg(
            ImmutableMap.of(
                "compatible_with",
                ImmutableList.of(
                    compatibleConfigSettingWithValues
                        .getBuildTarget()
                        .getUnflavoredBuildTarget())));

    BuckConfig incompatibleBuckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("section", ImmutableMap.of("config", "false")))
            .build();
    assertFalse(
        TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
            configurationRuleRegistry,
            targetNodeArg,
            platform,
            DependencyStack.root(),
            incompatibleBuckConfig));
  }

  private ConstructorArg createTargetNodeArg(Map<String, Object> rawNode) throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller marshaller = new DefaultConstructorArgMarshaller();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:target");

    DataTransferObjectDescriptor<TestDescriptionArg> builder =
        typeCoercerFactory.getNativeConstructorArgDescriptor(TestDescriptionArg.class);

    ImmutableMap.Builder<ParamName, Object> attributes = ImmutableMap.builder();
    for (Map.Entry<String, Object> attr : rawNode.entrySet()) {
      attributes.put(ParamName.bySnakeCase(attr.getKey()), attr.getValue());
    }
    attributes.put(ParamName.bySnakeCase("name"), "target");
    return marshaller.populate(
        TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
        projectFilesystem,
        new ThrowingSelectorListResolver(),
        SelectableConfigurationContextFactory.UNCONFIGURED,
        new ThrowingTargetConfigurationTransformer(),
        configurationRuleRegistry.getTargetPlatformResolver(),
        buildTarget,
        UnconfiguredTargetConfiguration.INSTANCE,
        DependencyStack.root(),
        builder,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        attributes.build(),
        LabelledAnySelectable.any());
  }

  static class TestRuleRuleDescription implements RuleDescription<AbstractTestDescriptionArg> {

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

  @RuleArg
  interface AbstractTestDescriptionArg extends BuildRuleArg {}
}
