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

package com.facebook.buck.core.starlark.rule;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.facebook.buck.core.select.NonCopyingSelectableConfigurationContext;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.core.starlark.rule.attr.impl.IntAttribute;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkDescriptionArgTest {

  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void throwsWhenInvalidFieldIsRequested() throws EvalException, LabelSyntaxException {

    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());

    expected.expect(NullPointerException.class);
    arg.getPostCoercionValue("baz");
  }

  @Test
  public void throwsWhenSettingWithAnInvalidName() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());

    expected.expect(IllegalStateException.class);
    expected.expectMessage("it was not one of the attributes");
    arg.setPostCoercionValue("not_declared", 1);
  }

  @Test
  public void throwsWhenSettingAfterBuilding() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());
    arg.setPostCoercionValue("name", "ohmy");
    arg.build();

    expected.expect(IllegalStateException.class);
    expected.expectMessage("after building an instance");
    arg.setPostCoercionValue("baz", 1);
  }

  @Test
  public void getsValuesThatHaveBeenSet() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());

    arg.setPostCoercionValue("baz", 1);
    assertEquals(1, arg.getPostCoercionValue("baz"));
  }

  @Test
  public void getsLabelsAndLicenses() throws LabelSyntaxException, EvalException {
    ImmutableSortedSet<DefaultBuildTargetSourcePath> licenses =
        ImmutableSortedSet.of(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:LICENSE")),
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:LICENSE2")));
    SkylarkDescriptionArg arg =
        new SkylarkDescriptionArg(FakeSkylarkUserDefinedRuleFactory.createSimpleRule());
    arg.setPostCoercionValue("labels", ImmutableSortedSet.of("foo", "bar"));
    arg.setPostCoercionValue("licenses", licenses);

    assertEquals(ImmutableSortedSet.of("bar", "foo"), arg.getLabels());
    assertEquals(licenses, arg.getLicenses());
  }

  @Test
  public void defaultValuesUsedWhenMarshalling()
      throws LabelSyntaxException, EvalException, CoerceFailedException {
    DefaultConstructorArgMarshaller marshaller = new DefaultConstructorArgMarshaller();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellNameResolver cellNameResolver =
        DefaultCellPathResolver.create(
                filesystem.getRootPath(), FakeBuckConfig.builder().build().getConfig())
            .getCellNameResolver();
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);
    SelectableConfigurationContext configurationContext =
        NonCopyingSelectableConfigurationContext.INSTANCE;
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    TargetConfiguration hostConfiguration = UnconfiguredTargetConfiguration.INSTANCE;
    DependencyStack dependencyStack = DependencyStack.root();
    KnownUserDefinedRuleTypes knownRuleTypes = new KnownUserDefinedRuleTypes();

    SkylarkUserDefinedRule fakeRule =
        FakeSkylarkUserDefinedRuleFactory.createRuleFromCallable(
            "some_rule",
            ImmutableMap.of(
                "defaulted", IntAttribute.of(5, "", false, ImmutableList.of()),
                "_hidden", IntAttribute.of(10, "", false, ImmutableList.of())),
            "//foo:bar.bzl",
            (ctx) -> Runtime.NONE);
    knownRuleTypes.addRule(fakeRule);

    DataTransferObjectDescriptor<SkylarkDescriptionArg> constructorArgDescriptor =
        knownRuleTypes
            .getDescriptorByNameChecked("//foo:bar.bzl:some_rule", SkylarkDescriptionArg.class)
            .getDtoDescriptor()
            .apply(new DefaultTypeCoercerFactory());

    ImmutableMap<String, Object> attributes = ImmutableMap.of("name", "bar");
    ImmutableMap<String, Object> attributes2 = ImmutableMap.of("name", "bar", "defaulted", 1);

    SkylarkDescriptionArg populated1 =
        marshaller.populate(
            cellNameResolver,
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            configurationContext,
            target,
            hostConfiguration,
            dependencyStack,
            constructorArgDescriptor,
            ImmutableSet.builder(),
            ImmutableSet.builder(),
            attributes);

    SkylarkDescriptionArg populated2 =
        marshaller.populate(
            cellNameResolver,
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            configurationContext,
            target,
            hostConfiguration,
            dependencyStack,
            constructorArgDescriptor,
            ImmutableSet.builder(),
            ImmutableSet.builder(),
            attributes2);

    assertEquals(ImmutableSortedSet.of(), populated1.getPostCoercionValue("labels"));
    assertEquals(5, populated1.getPostCoercionValue("defaulted"));
    assertEquals(10, populated1.getPostCoercionValue("_hidden"));

    assertEquals(ImmutableSortedSet.of(), populated2.getPostCoercionValue("labels"));
    assertEquals(1, populated2.getPostCoercionValue("defaulted"));
    assertEquals(10, populated2.getPostCoercionValue("_hidden"));
  }
}
