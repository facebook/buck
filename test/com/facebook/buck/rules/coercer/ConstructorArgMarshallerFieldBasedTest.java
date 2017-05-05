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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

@SuppressWarnings("unused") // Many unused fields in sample DTO objects.
public class ConstructorArgMarshallerFieldBasedTest {

  public static final BuildTarget TARGET = BuildTargetFactory.newInstance("//example/path:three");
  private Path basePath;
  private ConstructorArgMarshaller marshaller;
  private ProjectFilesystem filesystem;

  @Rule public ExpectedException mExpected = ExpectedException.none();

  @Before
  public void setUpInspector() {
    basePath = Paths.get("example", "path");
    marshaller = new ConstructorArgMarshaller(new DefaultTypeCoercerFactory());
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void shouldPopulateAStringValue() throws Exception {
    DtoWithString dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithString.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("string", "cheese"));

    assertEquals("cheese", dto.string);
  }

  @Test
  public void shouldPopulateABooleanValue() throws Exception {
    DtoWithBoolean dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithBoolean.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("value", true));

    assertTrue(dto.value);
  }

  @Test
  public void shouldPopulateBuildTargetValues() throws Exception {
    DtoWithBuildTargets dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithBuildTargets.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "target", "//cake:walk",
                "local", ":fish"));

    assertEquals(
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//cake:walk"), dto.target);
    assertEquals(
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example/path:fish"), dto.local);
  }

  @Test
  public void shouldPopulateANumericValue() throws Exception {
    DtoWithLong dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithLong.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("number", 42L));

    assertEquals(42, dto.number);
  }

  @Test
  public void shouldPopulateSourcePaths() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:peas");
    DtoWithSourcePaths dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSourcePaths.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "filePath", "cheese.txt",
                "targetPath", ":peas"));

    assertEquals(
        new PathSourcePath(projectFilesystem, Paths.get("example/path/cheese.txt")), dto.filePath);
    assertEquals(new DefaultBuildTargetSourcePath(target), dto.targetPath);
  }

  @Test
  public void shouldPopulateAnImmutableSortedSet() throws Exception {
    BuildTarget t1 = BuildTargetFactory.newInstance("//please/go:here");
    BuildTarget t2 = BuildTargetFactory.newInstance("//example/path:there");

    // Note: the ordering is reversed from the natural ordering
    DtoWithImmutableSortedSet dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithImmutableSortedSet.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "deps", ImmutableList.of("//please/go:here", ":there")));

    assertEquals(ImmutableSortedSet.of(t2, t1), dto.deps);
  }

  @Test
  public void shouldPopulateSets() throws Exception {
    DtoWithSetOfPaths dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSetOfPaths.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("paths", ImmutableList.of("one", "two")));

    assertEquals(
        ImmutableSet.of(Paths.get("example/path/one"), Paths.get("example/path/two")), dto.paths);
  }

  @Test
  public void shouldPopulateLists() throws Exception {
    DtoWithListOfStrings dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithListOfStrings.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("list", ImmutableList.of("alpha", "beta")));

    assertEquals(ImmutableList.of("alpha", "beta"), dto.list);
  }

  @Test
  public void onlyFieldNamedDepsAreConsideredDeclaredDeps() throws Exception {
    final String dep = "//is/a/declared:dep";
    final String notDep = "//is/not/a/declared:dep";

    BuildTarget declaredDep = BuildTargetFactory.newInstance(dep);

    Map<String, Object> args =
        ImmutableMap.of(
            "deps", ImmutableList.of(dep),
            "notdeps", ImmutableList.of(notDep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    DtoWithDepsAndNotDeps dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDepsAndNotDeps.class,
            declaredDeps,
            args);

    assertEquals(ImmutableSet.of(declaredDep), declaredDeps.build());
  }

  @Test
  public void fieldsWithIsDepEqualsFalseHintAreNotTreatedAsDeps() throws Exception {
    final String dep = "//should/be:ignored";

    Map<String, Object> args = ImmutableMap.of("deps", ImmutableList.of(dep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    DtoWithFakeDeps dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithFakeDeps.class,
            declaredDeps,
            args);

    assertEquals(ImmutableSet.of(), declaredDeps.build());
  }

  @Test
  public void optionalCollectionsWithoutAValueWillBeSetToAnEmptyOptionalCollection()
      throws Exception {
    Map<String, Object> args = ImmutableMap.of();
    // Deliberately not populating args

    DtoWithOptionalSetOfStrings dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithOptionalSetOfStrings.class,
            ImmutableSet.builder(),
            args);

    assertEquals(Optional.empty(), dto.strings);
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToAttemptToSetASingleValueToACollection() throws Exception {

    DtoWithString dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithString.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("string", ImmutableList.of("a", "b")));
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToAttemptToSetACollectionToASingleValue() throws Exception {
    DtoWithSetOfStrings dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSetOfStrings.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("strings", "isn't going to happen"));
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToSetTheWrongTypeOfValueInACollection() throws Exception {
    DtoWithSetOfStrings dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSetOfStrings.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("strings", ImmutableSet.of(true, false)));
  }

  @Test
  public void shouldNormalizePaths() throws Exception {
    DtoWithPath dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithPath.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("path", "./bar/././fish.txt"));

    assertEquals(basePath.resolve("bar/fish.txt").normalize(), dto.path);
  }

  @Test(expected = RuntimeException.class)
  public void lowerBoundGenericTypesCauseAnException() throws Exception {

    class Dto {
      public List<? super BuildTarget> nope;
    }

    Dto dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            Dto.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("nope", ImmutableList.of("//will/not:happen")));
  }

  @Test
  public void shouldSetBuildTargetParameters() throws Exception {
    DtoWithBuildTargetList dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithBuildTargetList.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "single", "//com/example:cheese",
                "sameBuildFileTarget", ":cake",
                "targets", ImmutableList.of(":cake", "//com/example:cheese")));

    BuildTarget cheese = BuildTargetFactory.newInstance("//com/example:cheese");
    BuildTarget cake = BuildTargetFactory.newInstance("//example/path:cake");

    assertEquals(cheese, dto.single);
    assertEquals(cake, dto.sameBuildFileTarget);
    assertEquals(ImmutableList.of(cake, cheese), dto.targets);
  }

  @Test
  public void specifyingZeroIsNotConsideredOptional() throws Exception {
    DtoWithOptionalInteger dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithOptionalInteger.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("number", 0));

    assertTrue(dto.number.isPresent());
    assertEquals(Optional.of(0), dto.number);
  }

  @Test
  public void canPopulateSimpleConstructorArgFromBuildFactoryParams() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:path");

    ImmutableMap<String, Object> args =
        ImmutableMap.<String, Object>builder()
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
    DtoWithVariousTypes dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithVariousTypes.class,
            ImmutableSet.builder(),
            args);

    assertEquals("cheese", dto.required);
    assertEquals("cake", dto.notRequired.get());
    assertEquals(42, dto.num);
    assertEquals(Optional.of(88L), dto.optionalLong);
    assertTrue(dto.needed);
    assertEquals(Optional.empty(), dto.notNeeded);
    DefaultBuildTargetSourcePath expected = new DefaultBuildTargetSourcePath(target);
    assertEquals(expected, dto.aSrcPath);
    assertEquals(Paths.get("example/path/NotFile.java"), dto.notAPath.get());
  }

  @Test
  public void shouldPopulateDefaultValuesAsBeingAbsent() throws Exception {
    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = Maps.newHashMap();
    args.put("defaultString", null);
    args.put("defaultSourcePath", null);
    DtoWithOptionalValues dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithOptionalValues.class,
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
    DtoWithDefaultValues dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDefaultValues.class,
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
    DtoWithDefaultValues dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDefaultValues.class,
            ImmutableSet.builder(),
            args);

    assertThat(dto.something, is("bar"));
    assertThat(dto.things, is(ImmutableList.of("qux", "quz")));
    assertThat(dto.another, is(1234));
    assertThat(dto.beGood, is(false));
  }

  @Test
  public void shouldResolveCollectionOfSourcePaths() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:manifest");

    DtoWithSetOfSourcePaths dto =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSetOfSourcePaths.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "srcs", ImmutableList.of("main.py", "lib/__init__.py", "lib/manifest.py")));

    ImmutableSet<String> observedValues =
        dto.srcs
            .stream()
            .map(input -> ((PathSourcePath) input).getRelativePath().toString())
            .collect(MoreCollectors.toImmutableSet());
    assertEquals(
        ImmutableSet.of(
            Paths.get("example/path/main.py").toString(),
            Paths.get("example/path/lib/__init__.py").toString(),
            Paths.get("example/path/lib/manifest.py").toString()),
        observedValues);
  }

  public static class DtoWithString extends AbstractDescriptionArg {
    public String string;
  }

  public static class DtoWithListOfStrings extends AbstractDescriptionArg {
    public List<String> list;
  }

  public static class DtoWithSetOfStrings extends AbstractDescriptionArg {
    public Set<String> strings;
  }

  public static class DtoWithOptionalSetOfStrings extends AbstractDescriptionArg {
    public Optional<Set<String>> strings;
  }

  public static class DtoWithPath extends AbstractDescriptionArg {
    public Path path;
  }

  public static class DtoWithSetOfPaths extends AbstractDescriptionArg {
    public Set<Path> paths;
  }

  public static class DtoWithBoolean extends AbstractDescriptionArg {
    public boolean value;
  }

  public static class DtoWithFakeDeps extends AbstractDescriptionArg {
    @Hint(isDep = false)
    public Optional<Set<BuildTarget>> deps;
  }

  public static class DtoWithOptionalInteger extends AbstractDescriptionArg {
    public Optional<Integer> number;
  }

  public static class EmptyDto extends AbstractDescriptionArg {}

  public static class DtoWithBuildTargets extends AbstractDescriptionArg {
    public BuildTarget target;
    public BuildTarget local;
  }

  public static class DtoWithBuildTargetList extends AbstractDescriptionArg {
    public BuildTarget single;
    public BuildTarget sameBuildFileTarget;
    public List<BuildTarget> targets;
  }

  public static class DtoWithSourcePaths extends AbstractDescriptionArg {
    public SourcePath filePath;
    public SourcePath targetPath;
  }

  public static class DtoWithDepsAndNotDeps extends AbstractDescriptionArg {
    public Optional<Set<BuildTarget>> deps;
    public Optional<Set<BuildTarget>> notdeps;
  }

  public static class DtoWithImmutableSortedSet extends AbstractDescriptionArg {
    public ImmutableSortedSet<BuildTarget> deps;
  }

  public static class DtoWithSetOfSourcePaths extends AbstractDescriptionArg {
    public ImmutableSortedSet<SourcePath> srcs;
  }

  public static class DtoWithLong extends AbstractDescriptionArg {
    public long number;
  }

  public static class DtoWithVariousTypes extends AbstractDescriptionArg {
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

  public static class DtoWithOptionalValues extends AbstractDescriptionArg {
    public Optional<String> noString;
    public Optional<String> defaultString;

    public Optional<SourcePath> noSourcePath;
    public Optional<SourcePath> defaultSourcePath;
  }

  public static class DtoWithDefaultValues extends AbstractDescriptionArg {
    public String something = "foo";
    public List<String> things = ImmutableList.of("bar");
    public int another = 365;
    public Boolean beGood = true;
  }
}
