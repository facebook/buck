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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.impl.ThrowingTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.FakeMultiPlatform;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.select.NonCopyingSelectableConfigurationContext;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.TestSelectable;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.core.select.impl.ThrowingSelectableConfigurationContext;
import com.facebook.buck.core.select.impl.ThrowingSelectorListResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePathFactoryForTests;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.DefaultSelectableConfigurationContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConstructorArgMarshallerImmutableTest {

  public static final BuildTarget TARGET = BuildTargetFactory.newInstance("//example/path:three");
  private ConstructorArgMarshaller marshaller;
  private ProjectFilesystem filesystem;

  @Rule public ExpectedException expected = ExpectedException.none();

  @Before
  public void setUpInspector() {
    marshaller = new DefaultConstructorArgMarshaller();
    filesystem = new FakeProjectFilesystem();
  }

  private <T extends BuildRuleArg> T invokePopulate2(
      Class<T> constructorClass,
      Map<String, ?> attributes,
      ImmutableSet.Builder<BuildTarget> declaredDeps)
      throws CoerceFailedException {
    HashMap<String, Object> attributesWithName = new HashMap<>(attributes);
    attributesWithName.putIfAbsent("name", "the name");

    ImmutableSet.Builder<BuildTarget> configurationDeps = ImmutableSet.builder();
    T result =
        marshaller.populate(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            new ThrowingSelectorListResolver(),
            new ThrowingTargetConfigurationTransformer(),
            new ThrowingSelectableConfigurationContext(),
            TARGET,
            UnconfiguredTargetConfiguration.INSTANCE,
            DependencyStack.root(),
            builder(constructorClass),
            declaredDeps,
            configurationDeps,
            attributesWithName);
    assertEquals(ImmutableSet.of(), configurationDeps.build());
    return result;
  }

  private <T extends BuildRuleArg> T invokePopulate(
      Class<T> constructorClass, Map<String, ?> attributes) throws CoerceFailedException {
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    T result = invokePopulate2(constructorClass, attributes, declaredDeps);
    assertEquals(ImmutableSet.of(), declaredDeps.build());
    return result;
  }

  <T extends ConstructorArg> DataTransferObjectDescriptor<T> builder(Class<T> dtoClass) {
    return new DefaultTypeCoercerFactory().getConstructorArgDescriptor(dtoClass);
  }

  @Test
  public void shouldPopulateAStringValue() throws Exception {

    DtoWithString built =
        invokePopulate(DtoWithString.class, ImmutableMap.<String, Object>of("string", "cheese"));

    assertEquals("cheese", built.getString());
  }

  @Test
  public void shouldPopulateABooleanValue() throws Exception {
    DtoWithBoolean built =
        invokePopulate(
            DtoWithBoolean.class,
            ImmutableMap.<String, Object>of(
                "booleanOne", true,
                "booleanTwo", true));

    assertTrue(built.getBooleanOne());
    assertTrue(built.isBooleanTwo());
  }

  @Test
  public void shouldPopulateBuildTargetValues() throws Exception {
    DtoWithBuildTargets built =
        invokePopulate(
            DtoWithBuildTargets.class,
            ImmutableMap.<String, Object>of(
                "target", UnconfiguredBuildTargetParser.parse("//cake:walk"),
                "local", UnconfiguredBuildTargetParser.parse("//example/path:fish")));

    assertEquals(BuildTargetFactory.newInstance("//cake:walk"), built.getTarget());
    assertEquals(BuildTargetFactory.newInstance("//example/path:fish"), built.getLocal());
  }

  @Test
  public void shouldPopulateANumericValue() throws Exception {
    DtoWithLong built =
        invokePopulate(DtoWithLong.class, ImmutableMap.<String, Object>of("number", 42L));

    assertEquals(42, built.getNumber());
  }

  @Test
  public void shouldPopulateSourcePaths() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:peas");
    DtoWithSourcePaths built =
        invokePopulate(
            DtoWithSourcePaths.class,
            ImmutableMap.<String, Object>of(
                "filePath",
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath(
                    "example/path/cheese.txt"),
                "targetPath",
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath(
                    "//example/path:peas")));

    assertEquals(
        PathSourcePath.of(projectFilesystem, Paths.get("example/path/cheese.txt")),
        built.getFilePath());
    assertEquals(DefaultBuildTargetSourcePath.of(target), built.getTargetPath());
  }

  @Test
  public void shouldPopulateAnImmutableSortedSet() throws Exception {
    BuildTarget t1 = BuildTargetFactory.newInstance("//please/go:here");
    BuildTarget t2 = BuildTargetFactory.newInstance("//example/path:there");

    // Note: the ordering is reversed from the natural ordering
    DtoWithImmutableSortedSet built =
        invokePopulate(
            DtoWithImmutableSortedSet.class,
            ImmutableMap.<String, Object>of(
                "stuff",
                ImmutableList.of(
                    t1.getUnconfiguredBuildTarget(), t2.getUnconfiguredBuildTarget())));

    assertEquals(ImmutableSortedSet.of(t2, t1), built.getStuff());
  }

  @Test
  public void shouldPopulateLists() throws Exception {
    DtoWithListOfStrings built =
        invokePopulate(
            DtoWithListOfStrings.class,
            ImmutableMap.<String, Object>of("list", ImmutableList.of("alpha", "beta")));

    assertEquals(ImmutableList.of("alpha", "beta"), built.getList());
  }

  @Test
  public void onlyFieldNamedDepsAreConsideredDeclaredDeps() throws Exception {
    UnconfiguredBuildTarget dep = UnconfiguredBuildTargetParser.parse("//is/a/declared:dep");
    UnconfiguredBuildTarget notDep = UnconfiguredBuildTargetParser.parse("//is/not/a/declared:dep");

    BuildTarget declaredDep = dep.configure(UnconfiguredTargetConfiguration.INSTANCE);

    Map<String, Object> args = new HashMap<>();
    args.put("deps", ImmutableList.of(dep));
    args.put("notdeps", ImmutableList.of(notDep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    DtoWithDepsAndNotDeps built = invokePopulate2(DtoWithDepsAndNotDeps.class, args, declaredDeps);

    assertEquals(ImmutableSet.of(declaredDep), declaredDeps.build());
    assertEquals(ImmutableSet.of(declaredDep), built.getDeps());
  }

  @Test
  public void fieldsWithIsDepEqualsFalseHintAreNotTreatedAsDeps() throws Exception {
    UnconfiguredBuildTarget dep = UnconfiguredBuildTargetParser.parse("//should/be:ignored");
    Map<String, Object> args = ImmutableMap.of("deps", ImmutableList.of(dep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    DtoWithFakeDeps built = invokePopulate2(DtoWithFakeDeps.class, args, declaredDeps);

    assertEquals(ImmutableSet.of(), declaredDeps.build());
    assertEquals(
        ImmutableSet.of(dep.configure(UnconfiguredTargetConfiguration.INSTANCE)), built.getDeps());
  }

  @Test
  public void collectionsAreOptional() throws Exception {
    DtoWithCollections built = invokePopulate(DtoWithCollections.class, ImmutableMap.of());

    assertEquals(ImmutableSet.of(), built.getSet());
    assertEquals(ImmutableSet.of(), built.getImmutableSet());
    assertEquals(ImmutableSortedSet.of(), built.getSortedSet());
    assertEquals(ImmutableSortedSet.of(), built.getImmutableSortedSet());
    assertEquals(ImmutableList.of(), built.getList());
    assertEquals(ImmutableList.of(), built.getImmutableList());
    assertEquals(ImmutableMap.of(), built.getMap());
    assertEquals(ImmutableMap.of(), built.getImmutableMap());
  }

  @Test
  public void optionalCollectionsWithoutAValueWillBeSetToAnEmptyOptionalCollection()
      throws Exception {
    // Deliberately not populating args
    Map<String, Object> args = ImmutableMap.of();

    DtoWithOptionalSetOfStrings built = invokePopulate(DtoWithOptionalSetOfStrings.class, args);

    assertEquals(Optional.empty(), built.getStrings());
  }

  @Test
  public void errorsOnMissingValues() throws Exception {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(containsString(TARGET.getFullyQualifiedName()));
    expected.expectMessage(containsString("missing required"));
    expected.expectMessage(containsString("booleanOne"));
    expected.expectMessage(containsString("booleanTwo"));

    invokePopulate(DtoWithBoolean.class, ImmutableMap.of());
  }

  @Test
  public void errorsOnBadChecks() throws Exception {
    expected.expect(RuntimeException.class);
    expected.expectMessage(containsString(TARGET.getFullyQualifiedName()));
    expected.expectMessage(containsString("NOT THE SECRETS"));

    invokePopulate(DtoWithCheck.class, ImmutableMap.of("string", "secrets"));
  }

  @Test
  public void noErrorsOnGoodChecks() throws Exception {
    DtoWithCheck built =
        invokePopulate(DtoWithCheck.class, ImmutableMap.of("string", "not secrets"));
    assertEquals("not secrets", built.getString());
  }

  @Test
  public void shouldSetBuildTargetParameters() throws Exception {
    DtoWithBuildTargetList built =
        invokePopulate(
            DtoWithBuildTargetList.class,
            ImmutableMap.<String, Object>of(
                "single", UnconfiguredBuildTargetParser.parse("//com/example:cheese"),
                "sameBuildFileTarget", UnconfiguredBuildTargetParser.parse("//example/path:cake"),
                "targets",
                    ImmutableList.of(
                        UnconfiguredBuildTargetParser.parse("//example/path:cake"),
                        UnconfiguredBuildTargetParser.parse("//com/example:cheese"))));

    BuildTarget cheese = BuildTargetFactory.newInstance("//com/example:cheese");
    BuildTarget cake = BuildTargetFactory.newInstance("//example/path:cake");

    assertEquals(cheese, built.getSingle());
    assertEquals(cake, built.getSameBuildFileTarget());
    assertEquals(ImmutableList.of(cake, cheese), built.getTargets());
  }

  @Test
  public void canPopulateSimpleConstructorArgFromBuildFactoryParams() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:path");

    ImmutableMap<String, Object> args =
        ImmutableMap.<String, Object>builder()
            .put("required", "cheese")
            .put("notRequired", Optional.of("cake"))
            // Long because that's what comes from python.
            .put("num", 42)
            .put("optionalLong", Optional.of(88L))
            .put("needed", true)
            // Skipping optional boolean.
            .put(
                "aSrcPath",
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath("//example/path:path"))
            .put("aPath", Paths.get("./File.java"))
            .put("notAPath", Optional.of(Paths.get("example/path/NotFile.java")))
            .build();
    DtoWithVariousTypes built = invokePopulate(DtoWithVariousTypes.class, args);

    assertEquals("cheese", built.getRequired());
    assertEquals("cake", built.getNotRequired().get());
    assertEquals(42, built.getNum());
    assertEquals(Optional.of(88L), built.getOptionalLong());
    assertTrue(built.isNeeded());
    assertEquals(Optional.empty(), built.isNotNeeded());
    DefaultBuildTargetSourcePath expected = DefaultBuildTargetSourcePath.of(target);
    assertEquals(expected, built.getASrcPath());
    assertEquals(Paths.get("example/path/NotFile.java"), built.getNotAPath().get());
  }

  @Test
  public void shouldNotPopulateDefaultValues() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = new HashMap<>();
    args.put("defaultString", null);
    args.put("defaultSourcePath", null);
    DtoWithOptionalValues built = invokePopulate(DtoWithOptionalValues.class, args);

    assertEquals(Optional.empty(), built.getNoString());
    assertEquals(Optional.empty(), built.getDefaultString());
    assertEquals(Optional.empty(), built.getNoSourcePath());
    assertEquals(Optional.empty(), built.getDefaultSourcePath());
  }

  @Test
  public void shouldRespectSpecifiedDefaultValues() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = new HashMap<>();
    args.put("something", null);
    args.put("things", null);
    DtoWithDefaultValues built = invokePopulate(DtoWithDefaultValues.class, args);

    assertEquals("foo", built.getSomething());
    assertEquals(ImmutableList.of("bar"), built.getThings());
    assertEquals(365, built.getMore());
    assertTrue(built.getBeGood());
  }

  @Test
  public void shouldAllowOverridingDefaultValues() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = new HashMap<>();
    args.put("something", "bar");
    args.put("things", ImmutableList.of("qux", "quz"));
    args.put("more", 1234);
    args.put("beGood", false);
    DtoWithDefaultValues built = invokePopulate(DtoWithDefaultValues.class, args);

    assertEquals("bar", built.getSomething());
    assertEquals(ImmutableList.of("qux", "quz"), built.getThings());
    assertEquals(1234, built.getMore());
    assertFalse(built.getBeGood());
  }

  @Test
  public void shouldResolveCollectionOfSourcePathsRelativeToTarget() throws Exception {
    DtoWithSetOfSourcePaths built =
        invokePopulate(
            DtoWithSetOfSourcePaths.class,
            ImmutableMap.<String, Object>of(
                "srcs",
                ImmutableList.of("main.py", "lib/__init__.py", "lib/manifest.py").stream()
                    .map(
                        p ->
                            new UnconfiguredSourcePath.Path(
                                CellRelativePath.of(
                                    CanonicalCellName.rootCell(),
                                    ForwardRelativePath.of("example/path/" + p))))
                    .collect(ImmutableList.toImmutableList())));

    ImmutableSet<String> observedValues =
        built.getSrcs().stream()
            .map(input -> ((PathSourcePath) input).getRelativePath().toString())
            .collect(ImmutableSet.toImmutableSet());
    assertEquals(
        ImmutableSet.of(
            Paths.get("example/path/main.py").toString(),
            Paths.get("example/path/lib/__init__.py").toString(),
            Paths.get("example/path/lib/manifest.py").toString()),
        observedValues);
  }

  @Test
  public void derivedMethodsAreIgnored() throws Exception {
    DtoWithDerivedAndOrdinaryMethods built =
        invokePopulate(
            DtoWithDerivedAndOrdinaryMethods.class,
            ImmutableMap.<String, Object>of("string", "tamarins"));
    assertEquals("tamarins", built.getString());
    assertEquals("TAMARINS", built.getUpper());
    assertEquals("constant", built.getConstant());
  }

  @Test
  public void specifyingDerivedValuesIsIgnored() throws Exception {
    DtoWithDerivedAndOrdinaryMethods built =
        invokePopulate(
            DtoWithDerivedAndOrdinaryMethods.class,
            ImmutableMap.<String, Object>of(
                "string", "tamarins",
                "upper", "WRONG"));
    assertEquals("tamarins", built.getString());
    assertEquals("TAMARINS", built.getUpper());
  }

  @Test
  public void defaultMethodFallsBackToDefault() throws Exception {
    InheritsFromHasDefaultMethod built =
        invokePopulate(InheritsFromHasDefaultMethod.class, ImmutableMap.of());
    assertEquals("foo", built.getString());
  }

  @Test
  public void defaultMethodCanBeSpecified() throws Exception {
    InheritsFromHasDefaultMethod built =
        invokePopulate(
            InheritsFromHasDefaultMethod.class, ImmutableMap.<String, Object>of("string", "bar"));
    assertEquals("bar", built.getString());
  }

  @Test
  public void populateWithConfiguringAttributesResolvesConfigurableAttributes() throws Exception {
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, true))));
    SelectorList<?> selectorList =
        new SelectorList<>(
            ImmutableList.of(
                new Selector<>(
                    ImmutableMap.of(
                        SelectorKey.DEFAULT,
                        "string1",
                        new SelectorKey(
                            ConfigurationBuildTargetFactoryForTests.newInstance("//x:y")),
                        "string2"),
                    ImmutableSet.of(),
                    ""),
                new Selector<>(
                    ImmutableMap.of(
                        SelectorKey.DEFAULT,
                        "string3",
                        new SelectorKey(
                            ConfigurationBuildTargetFactoryForTests.newInstance("//x:y")),
                        "string4"),
                    ImmutableSet.of(),
                    "")));
    TargetPlatformResolver targetPlatformResolver =
        (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE;
    SelectableConfigurationContext selectableConfigurationContext =
        DefaultSelectableConfigurationContext.of(
            FakeBuckConfig.builder().build(),
            UnconfiguredTargetConfiguration.INSTANCE,
            targetPlatformResolver);
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver);
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    ImmutableSet.Builder<BuildTarget> configurationDeps = ImmutableSet.builder();

    DtoWithString dto =
        marshaller.populate(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            selectableConfigurationContext,
            TARGET,
            UnconfiguredTargetConfiguration.INSTANCE,
            DependencyStack.root(),
            builder(DtoWithString.class),
            declaredDeps,
            configurationDeps,
            ImmutableMap.<String, Object>of("string", selectorList, "name", "unused"));

    assertEquals("string2string4", dto.getString());
    assertTrue(declaredDeps.build().isEmpty());
    assertEquals(ImmutableSet.of(selectableTarget), configurationDeps.build());
  }

  @Test
  public void populateWithConfiguringAttributesCopiesValuesToImmutable() throws Exception {
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    DtoWithString dto =
        marshaller.populate(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            NonCopyingSelectableConfigurationContext.INSTANCE,
            TARGET,
            UnconfiguredTargetConfiguration.INSTANCE,
            DependencyStack.root(),
            builder(DtoWithString.class),
            declaredDeps,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("string", "value", "name", "zzz"));
    assertEquals("value", dto.getString());
    assertTrue(declaredDeps.build().isEmpty());
  }

  @Test
  public void populateWithConfiguringAttributesCollectsDeclaredDeps() throws Exception {
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    BuildTarget dep = BuildTargetFactory.newInstance("//a/b:c");
    marshaller.populate(
        createCellRoots(filesystem).getCellNameResolver(),
        filesystem,
        selectorListResolver,
        targetConfigurationTransformer,
        NonCopyingSelectableConfigurationContext.INSTANCE,
        TARGET,
        UnconfiguredTargetConfiguration.INSTANCE,
        DependencyStack.root(),
        builder(DtoWithDepsAndNotDeps.class),
        declaredDeps,
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of(
            "deps", ImmutableList.of(dep.getUnconfiguredBuildTarget()), "name", "myname"));
    assertEquals(ImmutableSet.of(dep), declaredDeps.build());
  }

  @Test
  public void populateWithConfiguringAttributesSkipsMissingValues() throws Exception {
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);
    DtoWithOptionalSetOfStrings dto =
        marshaller.populate(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            NonCopyingSelectableConfigurationContext.INSTANCE,
            TARGET,
            UnconfiguredTargetConfiguration.INSTANCE,
            DependencyStack.root(),
            builder(DtoWithOptionalSetOfStrings.class),
            ImmutableSet.builder(),
            ImmutableSet.builder(),
            ImmutableMap.of("name", "something"));
    assertFalse(dto.getStrings().isPresent());
  }

  @Test
  public void populateWithConfiguringAttributesSplitsConfiguration() throws Exception {
    BuildTarget multiPlatformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:multi_platform");
    BuildTarget basePlatformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:base_platform");
    BuildTarget nestedPlatform1Target =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:nested_platform_1");
    BuildTarget nestedPlatform2Target =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:nested_platform_2");

    FakeMultiPlatform multiPlatform =
        new FakeMultiPlatform(
            multiPlatformTarget,
            new ConstraintBasedPlatform(basePlatformTarget, ImmutableSet.of()),
            ImmutableList.of(
                new ConstraintBasedPlatform(nestedPlatform1Target, ImmutableSet.of()),
                new ConstraintBasedPlatform(nestedPlatform2Target, ImmutableSet.of())));
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    TargetPlatformResolver targetPlatformResolver =
        (configuration, dependencyStack) -> multiPlatform;
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver);
    SelectableConfigurationContext selectableConfigurationContext =
        DefaultSelectableConfigurationContext.of(
            FakeBuckConfig.builder().build(),
            RuleBasedTargetConfiguration.of(multiPlatformTarget),
            targetPlatformResolver);

    DtoWithSplit dto =
        marshaller.populate(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            selectableConfigurationContext,
            TARGET,
            UnconfiguredTargetConfiguration.INSTANCE,
            DependencyStack.root(),
            builder(DtoWithSplit.class),
            ImmutableSet.builder(),
            ImmutableSet.builder(),
            ImmutableMap.of(
                "deps",
                ImmutableList.of(UnconfiguredBuildTargetParser.parse("//a/b:c")),
                "name",
                "testtesttest"));

    assertEquals(3, dto.getDeps().size());
    assertEquals(
        ImmutableSet.of(multiPlatformTarget, nestedPlatform1Target, nestedPlatform2Target),
        dto.getDeps().stream()
            .map(BuildTarget::getTargetConfiguration)
            .map(RuleBasedTargetConfiguration.class::cast)
            .map(RuleBasedTargetConfiguration::getTargetPlatform)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void populateWithExecSwitch() throws Exception {
    SelectorListResolver selectorListResolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    TargetConfigurationTransformer targetConfigurationTransformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);
    RuleBasedTargetConfiguration execConfiguration =
        RuleBasedTargetConfiguration.of(
            ConfigurationBuildTargetFactoryForTests.newInstance("//:p"));
    DtoWithExec d =
        marshaller.populate(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            selectorListResolver,
            targetConfigurationTransformer,
            NonCopyingSelectableConfigurationContext.INSTANCE,
            TARGET,
            execConfiguration,
            DependencyStack.root(),
            builder(DtoWithExec.class),
            ImmutableSet.builder(),
            ImmutableSet.builder(),
            ImmutableMap.of(
                "name",
                TARGET.getShortName(),
                "compiler",
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath("//tools:compiler")));
    BuildTargetSourcePath compiler = (BuildTargetSourcePath) d.getCompiler();
    assertEquals(execConfiguration, compiler.getTarget().getTargetConfiguration());
  }

  @RuleArg
  abstract static class AbstractDtoWithString implements BuildRuleArg {
    abstract String getString();
  }

  @RuleArg
  abstract static class AbstractDtoWithBoolean implements BuildRuleArg {
    abstract boolean getBooleanOne();

    abstract boolean isBooleanTwo();
  }

  @RuleArg
  abstract static class AbstractDtoWithListOfStrings implements BuildRuleArg {
    abstract List<String> getList();
  }

  @RuleArg
  abstract static class AbstractDtoWithFakeDeps implements BuildRuleArg {
    @Hint(isDep = false)
    abstract Set<BuildTarget> getDeps();

    @Hint(isDep = false)
    abstract Set<BuildTarget> getProvidedDeps();
  }

  @RuleArg
  abstract static class AbstractDtoWithCollections implements BuildRuleArg {
    abstract Set<String> getSet();

    abstract ImmutableSet<String> getImmutableSet();

    @Value.NaturalOrder
    abstract SortedSet<String> getSortedSet();

    @Value.NaturalOrder
    abstract ImmutableSortedSet<String> getImmutableSortedSet();

    abstract List<String> getList();

    abstract ImmutableList<String> getImmutableList();

    abstract Map<String, String> getMap();

    abstract ImmutableMap<String, String> getImmutableMap();
  }

  @RuleArg
  abstract static class AbstractDtoWithOptionalSetOfStrings implements BuildRuleArg {
    abstract Optional<Set<String>> getStrings();
  }

  @RuleArg
  abstract static class AbstractDtoWithSetOfStrings implements BuildRuleArg {
    abstract Set<String> getStrings();
  }

  @RuleArg
  abstract static class AbstractDtoWithPath implements BuildRuleArg {
    abstract Path getPath();
  }

  @RuleArg
  abstract static class AbstractEmptyImmutableDto implements BuildRuleArg {}

  @RuleArg
  abstract static class AbstractDtoWithBuildTargets implements BuildRuleArg {
    abstract BuildTarget getTarget();

    abstract BuildTarget getLocal();
  }

  @RuleArg
  abstract static class AbstractDtoWithLong implements BuildRuleArg {
    abstract long getNumber();
  }

  @RuleArg
  abstract static class AbstractDtoWithSourcePaths implements BuildRuleArg {
    abstract SourcePath getFilePath();

    abstract SourcePath getTargetPath();
  }

  @RuleArg
  abstract static class AbstractDtoWithImmutableSortedSet implements BuildRuleArg {
    abstract ImmutableSortedSet<BuildTarget> getStuff();
  }

  @RuleArg
  abstract static class AbstractDtoWithDeclaredDeps implements BuildRuleArg {
    abstract ImmutableSet<BuildTarget> getDeps();

    abstract ImmutableSet<SourcePath> getPaths();
  }

  @RuleArg
  abstract static class AbstractDtoWithSetOfPaths implements BuildRuleArg {
    abstract Set<Path> getPaths();
  }

  @RuleArg
  abstract static class AbstractDtoWithDepsAndNotDeps implements BuildRuleArg {
    abstract Set<BuildTarget> getDeps();

    abstract Set<BuildTarget> getNotDeps();
  }

  @RuleArg
  abstract static class AbstractDtoWithBuildTargetList implements BuildRuleArg {
    abstract BuildTarget getSingle();

    abstract BuildTarget getSameBuildFileTarget();

    abstract List<BuildTarget> getTargets();
  }

  @RuleArg
  abstract static class AbstractDtoWithVariousTypes implements BuildRuleArg {
    abstract String getRequired();

    abstract Optional<String> getNotRequired();

    abstract int getNum();

    abstract Optional<Long> getOptionalLong();

    abstract boolean isNeeded();

    abstract Optional<Boolean> isNotNeeded();

    abstract SourcePath getASrcPath();

    abstract Optional<SourcePath> getNotASrcPath();

    abstract Path getAPath();

    abstract Optional<Path> getNotAPath();
  }

  @RuleArg
  abstract static class AbstractDtoWithOptionalValues implements BuildRuleArg {
    abstract Optional<String> getNoString();

    abstract Optional<String> getDefaultString();

    abstract Optional<SourcePath> getNoSourcePath();

    abstract Optional<SourcePath> getDefaultSourcePath();
  }

  @RuleArg
  abstract static class AbstractDtoWithDefaultValues implements BuildRuleArg {
    @Value.Default
    public String getSomething() {
      return "foo";
    }

    @Value.Default
    public List<String> getThings() {
      return ImmutableList.of("bar");
    }

    @Value.Default
    public int getMore() {
      return 365;
    }

    @Value.Default
    public Boolean getBeGood() {
      return true;
    }
  }

  @RuleArg
  abstract static class AbstractDtoWithSetOfSourcePaths implements BuildRuleArg {
    abstract ImmutableSortedSet<SourcePath> getSrcs();
  }

  @RuleArg
  abstract static class AbstractDtoWithCheck implements BuildRuleArg {
    abstract String getString();

    @Value.Check
    public void check() {
      Preconditions.checkState(!getString().equals("secrets"), "NOT THE SECRETS!");
    }
  }

  @RuleArg
  abstract static class AbstractDtoWithDerivedAndOrdinaryMethods implements BuildRuleArg {
    abstract String getString();

    public String getConstant() {
      return "constant";
    }

    @Value.Derived
    public String getUpper() {
      return getString().toUpperCase();
    }
  }

  interface HasDefaultMethod extends BuildRuleArg {
    @Value.Default
    default String getString() {
      return "foo";
    }
  }

  @RuleArg
  interface AbstractInheritsFromHasDefaultMethod extends HasDefaultMethod {}

  @RuleArg
  abstract static class AbstractDtoWithSplit implements BuildRuleArg {
    @Hint(splitConfiguration = true)
    abstract ImmutableSortedSet<BuildTarget> getDeps();
  }

  @RuleArg
  abstract static class AbstractDtoWithExec implements BuildRuleArg {
    @Hint(execConfiguration = true)
    abstract SourcePath getCompiler();
  }
}
