/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@SuppressWarnings("unused") // Many unused fields in sample DTO objects.
public class ConstructorArgMarshallerTest {

  public static final BuildTarget TARGET = BuildTargetFactory.newInstance("//example/path:three");
  private Path basePath;
  private ConstructorArgMarshaller marshaller;
  private BuildRuleResolver ruleResolver;
  private ProjectFilesystem filesystem;

  @Before
  public void setUpInspector() {
    basePath = Paths.get("example", "path");
    marshaller = new ConstructorArgMarshaller(new DefaultTypeCoercerFactory(
        ObjectMappers.newDefaultInstance()));
    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void shouldNotPopulateAnEmptyArg() throws Exception {
    class Dto {
    }

    Dto dto = new Dto();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.of());
  }

  @Test
  public void shouldPopulateAStringValue() throws Exception {
    DtoWithString dto = new DtoWithString();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("string", "cheese"));

    assertEquals("cheese", dto.string);
  }

  @Test
  public void shouldPopulateABooleanValue() throws Exception {
    DtoWithBoolean dto = new DtoWithBoolean();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("value", true));

    assertTrue(dto.value);
  }

  @Test
  public void shouldPopulateBuildTargetValues() throws Exception {
    DtoWithBuildTargets dto = new DtoWithBuildTargets();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of(
            "target", "//cake:walk",
            "local", ":fish"
        ));

    assertEquals(BuildTargetFactory.newInstance(filesystem, "//cake:walk"), dto.target);
    assertEquals(BuildTargetFactory.newInstance(filesystem, "//example/path:fish"), dto.local);
  }

  @Test
  public void shouldPopulateANumericValue() throws Exception {
    DtoWithLong dto = new DtoWithLong();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("number", 42L));

    assertEquals(42, dto.number);
  }

  @Test
  public void shouldPopulateAPathValue() throws Exception {
    DtoWithRenamedPath dto = new DtoWithRenamedPath();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("somePath", "Fish.java"));

    assertEquals(Paths.get("example/path", "Fish.java"), dto.somePath);
  }

  @Test
  public void shouldPopulateSourcePaths() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:peas");
    SourcePathResolver resolver = new SourcePathResolver(new SourcePathRuleFinder(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     ));
    FakeBuildRule rule = new FakeBuildRule(target, resolver);
    ruleResolver.addToIndex(rule);
    DtoWithSourcePaths dto = new DtoWithSourcePaths();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of(
            "filePath", "cheese.txt",
            "targetPath", ":peas"));

    assertEquals(
        new PathSourcePath(projectFilesystem, Paths.get("example/path/cheese.txt")),
        dto.filePath);
    assertEquals(
        new BuildTargetSourcePath(rule.getBuildTarget()),
        dto.targetPath);
  }

  @Test
  public void shouldPopulateAnImmutableSortedSet() throws Exception {
    BuildTarget t1 = BuildTargetFactory.newInstance("//please/go:here");
    BuildTarget t2 = BuildTargetFactory.newInstance("//example/path:there");

    DtoWithImmutableSortedSet dto = new DtoWithImmutableSortedSet();
    // Note: the ordering is reversed from the natural ordering
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("deps", ImmutableList.of("//please/go:here", ":there")));

    assertEquals(ImmutableSortedSet.of(t2, t1), dto.deps);
  }

  @Test
  public void shouldPopulateSets() throws Exception {
    DtoWithSetOfPaths dto = new DtoWithSetOfPaths();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("paths", ImmutableList.of("one", "two")));

    assertEquals(
        ImmutableSet.of(Paths.get("example/path/one"), Paths.get("example/path/two")),
        dto.paths);
  }

  @Test
  public void shouldPopulateLists() throws Exception {
    DtoWithListOfStrings dto = new DtoWithListOfStrings();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("list", ImmutableList.of("alpha", "beta")));

    assertEquals(ImmutableList.of("alpha", "beta"), dto.list);
  }

  @Test
  public void onlyFieldNamedDepsAreConsideredDeclaredDeps() throws Exception {
    final String dep = "//is/a/declared:dep";
    final String notDep = "//is/not/a/declared:dep";

    BuildTarget declaredDep = BuildTargetFactory.newInstance(dep);

    DtoWithDepsAndNotDeps dto = new DtoWithDepsAndNotDeps();
    Map<String, Object> args = Maps.newHashMap();
    args.put("deps", ImmutableList.of(dep));
    args.put("notdeps", ImmutableList.of(notDep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        declaredDeps,
        ImmutableSet.builder(),
        args);

    assertEquals(ImmutableSet.of(declaredDep), declaredDeps.build());
  }

  @Test
  public void fieldsWithIsDepEqualsFalseHintAreNotTreatedAsDeps() throws Exception {
    final String dep = "//should/be:ignored";

    DtoWithFakeDeps dto = new DtoWithFakeDeps();
    Map<String, Object> args = Maps.newHashMap();
    args.put("deps", ImmutableList.of(dep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        declaredDeps,
        ImmutableSet.builder(),
        args);

    assertEquals(ImmutableSet.of(), declaredDeps.build());
  }

  @Test
  public void optionalCollectionsWithoutAValueWillBeSetToAnEmptyOptionalCollection()
      throws Exception {
    DtoWithOptionalSetOfStrings dto = new DtoWithOptionalSetOfStrings();
    Map<String, Object> args = Maps.newHashMap();
    // Deliberately not populating args

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        args);

    assertEquals(Optional.empty(), dto.strings);
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToAttemptToSetASingleValueToACollection() throws Exception {

    DtoWithString dto = new DtoWithString();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("string", ImmutableList.of("a", "b")));
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToAttemptToSetACollectionToASingleValue() throws Exception {
    DtoWithSetOfStrings dto = new DtoWithSetOfStrings();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("strings", "isn't going to happen"));
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToSetTheWrongTypeOfValueInACollection() throws Exception {
    DtoWithSetOfStrings dto = new DtoWithSetOfStrings();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("strings", ImmutableSet.of(true, false)));
  }

  @Test
  public void shouldNormalizePaths() throws Exception {
    DtoWithPath dto = new DtoWithPath();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("path", "./bar/././fish.txt"));

    assertEquals(basePath.resolve("bar/fish.txt").normalize(), dto.path);
  }

  @Test(expected = RuntimeException.class)
  public void lowerBoundGenericTypesCauseAnException() throws Exception {

    class Dto {
      public List<? super BuildTarget> nope;
    }

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        new Dto(),
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("nope", ImmutableList.of("//will/not:happen")));
  }

  @Test
  public void shouldSetBuildTargetParameters() throws Exception {
    DtoWithBuildTargetList dto = new DtoWithBuildTargetList();

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of(
            "single", "//com/example:cheese",
            "sameBuildFileTarget", ":cake",
            "targets", ImmutableList.of(":cake", "//com/example:cheese")
        ));

    BuildTarget cheese = BuildTargetFactory.newInstance("//com/example:cheese");
    BuildTarget cake = BuildTargetFactory.newInstance("//example/path:cake");

    assertEquals(cheese, dto.single);
    assertEquals(cake, dto.sameBuildFileTarget);
    assertEquals(ImmutableList.of(cake, cheese), dto.targets);
  }

  @Test
  public void upperBoundGenericTypesCauseValuesToBeSetToTheUpperBound() throws Exception {
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    ));
    BuildRule rule = new FakeBuildRule(
        BuildTargetFactory.newInstance("//will:happen"), pathResolver);
    ruleResolver.addToIndex(rule);
    DtoWithWildcardList dto = new DtoWithWildcardList();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of(
            "yup",
            ImmutableList.of(rule.getBuildTarget().getFullyQualifiedName())));

    BuildTargetSourcePath path = new BuildTargetSourcePath(rule.getBuildTarget());
    assertEquals(ImmutableList.of(path), dto.yup);
  }

  @Test
  public void specifyingZeroIsNotConsideredOptional() throws Exception {
    DtoWithOptionalInteger dto = new DtoWithOptionalInteger();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of("number", 0));

    assertTrue(dto.number.isPresent());
    assertEquals(Optional.of(0), dto.number);
  }

  @Test
  public void canPopulateSimpleConstructorArgFromBuildFactoryParams() throws Exception {
    SourcePathResolver resolver = new SourcePathResolver(new SourcePathRuleFinder(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     ));
    FakeBuildRule expectedRule = new FakeBuildRule(
        BuildTargetFactory.newInstance("//example/path:path"),
        resolver);
    ruleResolver.addToIndex(expectedRule);

    ImmutableMap<String, Object> args = ImmutableMap.<String, Object>builder()
        .put("required", "cheese")
        .put("notRequired", "cake")
        // Long because that's what comes from python.
        .put("num", 42L)
        .put("optionalLong", 88L)
        .put("needed", true)
        // Skipping optional boolean.
        .put("aSrcPath", ":path")
        .put("aPath", "./File.java")
        .put("notAPath", "./NotFile.java")
        .build();
    DtoWithVariousTypes dto = new DtoWithVariousTypes();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        args);

    assertEquals("cheese", dto.required);
    assertEquals("cake", dto.notRequired.get());
    assertEquals(42, dto.num);
    assertEquals(Optional.of(88L), dto.optionalLong);
    assertTrue(dto.needed);
    assertEquals(Optional.empty(), dto.notNeeded);
    BuildTargetSourcePath expected = new BuildTargetSourcePath(expectedRule.getBuildTarget());
    assertEquals(expected, dto.aSrcPath);
    assertEquals(Paths.get("example/path/NotFile.java"), dto.notAPath.get());
  }

  @Test
  public void shouldPopulateDefaultValuesAsBeingAbsent() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = Maps.newHashMap();
    args.put("defaultString", null);
    args.put("defaultSourcePath", null);
    DtoWithOptionalValues dto = new DtoWithOptionalValues();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        args);

    assertEquals(Optional.empty(), dto.noString);
    assertEquals(Optional.empty(), dto.defaultString);
    assertEquals(Optional.empty(), dto.noSourcePath);
    assertEquals(Optional.empty(), dto.defaultSourcePath);
  }

  @Test
  public void shouldRespectSpecifiedDefaultValues() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = Maps.newHashMap();
    args.put("something", null);
    args.put("things", null);
    args.put("another", null);
    DtoWithDefaultValues dto = new DtoWithDefaultValues();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        args);

    assertThat(dto.something, is("foo"));
    assertThat(dto.things, is(ImmutableList.of("bar")));
    assertThat(dto.another, is(365));
    assertThat(dto.beGood, is(true));
  }

  @Test
  public void shouldAllowOverridingDefaultValues() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = Maps.newHashMap();
    args.put("something", "bar");
    args.put("things", ImmutableList.of("qux", "quz"));
    args.put("another", 1234L);
    args.put("beGood", false);
    DtoWithDefaultValues dto = new DtoWithDefaultValues();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        args);

    assertThat(dto.something, is("bar"));
    assertThat(dto.things, is(ImmutableList.of("qux", "quz")));
    assertThat(dto.another, is(1234));
    assertThat(dto.beGood, is(false));
  }

  @Test
  public void shouldResolveCollectionOfSourcePaths() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:manifest");
    BuildRule rule = new FakeBuildRule(
        target, new SourcePathResolver(new SourcePathRuleFinder(resolver)));
    resolver.addToIndex(rule);

    DtoWithSetOfSourcePaths dto = new DtoWithSetOfSourcePaths();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.builder(),
        ImmutableSet.builder(),
        ImmutableMap.<String, Object>of(
            "srcs",
            ImmutableList.of("main.py", "lib/__init__.py", "lib/manifest.py")));

    ImmutableSet<String> observedValues = dto.srcs.stream()
        .map(input -> ((PathSourcePath) input).getRelativePath().toString())
        .collect(MoreCollectors.toImmutableSet());
    assertEquals(
        ImmutableSet.of(
            Paths.get("example/path/main.py").toString(),
            Paths.get("example/path/lib/__init__.py").toString(),
            Paths.get("example/path/lib/manifest.py").toString()),
        observedValues);
  }

  @Test(expected = HumanReadableException.class)
  public void bogusVisibilityGivesFriendlyError() throws Exception {
    EmptyDto dto = new EmptyDto();
    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<VisibilityPattern>builder(),
        ImmutableMap.<String, Object>of(
            "visibility", ImmutableList.of(":marmosets")));
  }

  public static class DtoWithString {
    public String string;
  }

  public static class DtoWithListOfStrings {
    public List<String> list;
  }

  public static class DtoWithSetOfStrings {
    public Set<String> strings;
  }

  public static class DtoWithOptionalSetOfStrings {
    public Optional<Set<String>> strings;
  }

  public static class DtoWithPath {
    public Path path;
  }

  public static class DtoWithSetOfPaths {
    public Set<Path> paths;
  }

  public static class DtoWithBoolean {
    public boolean value;
  }

  public static class DtoWithFakeDeps {
    @Hint(isDep = false)
    public Optional<Set<BuildTarget>> deps;
  }

  public static class DtoWithOptionalInteger {
    public Optional<Integer> number;
  }

  public static class DtoWithRenamedPath {
    @Hint(name = "some_path")
    public Path somePath;
  }

  public static class EmptyDto {}

  public static class DtoWithBuildTargets {
    public BuildTarget target;
    public BuildTarget local;
  }

  public static class DtoWithBuildTargetList {
    public BuildTarget single;
    public BuildTarget sameBuildFileTarget;
    public List<BuildTarget> targets;
  }

  public static class DtoWithSourcePaths {
    public SourcePath filePath;
    public SourcePath targetPath;
  }

  public static class DtoWithDepsAndNotDeps {
    public Optional<Set<BuildTarget>> deps;
    public Optional<Set<BuildTarget>> notdeps;
  }

  public static class DtoWithImmutableSortedSet {
    public ImmutableSortedSet<BuildTarget> deps;
  }

  public static class DtoWithSetOfSourcePaths {
    public ImmutableSortedSet<SourcePath> srcs;
  }

  public static class DtoWithLong {
    public long number;
  }

  public static class DtoWithWildcardList {
    public List<? extends SourcePath> yup;
  }

  public static class DtoWithVariousTypes {
    public String required;
    public Optional<String> notRequired;

    public int num;
    public Optional<Long> optionalLong;

    public boolean needed;
    public Optional<Boolean> notNeeded;

    public SourcePath aSrcPath;
    public Optional<SourcePath> notASrcPath;

    public Path aPath;
    public Optional<Path> notAPath;
  }

  public static class DtoWithOptionalValues {
    public Optional<String> noString;
    public Optional<String> defaultString;

    public Optional<SourcePath> noSourcePath;
    public Optional<SourcePath> defaultSourcePath;
  }

  public static class DtoWithDefaultValues {
    public String something = "foo";
    public List<String> things = ImmutableList.of("bar");
    public int another = 365;
    public Boolean beGood = true;
  }

}
