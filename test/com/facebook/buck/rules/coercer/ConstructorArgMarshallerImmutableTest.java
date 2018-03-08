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
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
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
  private Path basePath;
  private ConstructorArgMarshaller marshaller;
  private ProjectFilesystem filesystem;

  @Rule public ExpectedException expected = ExpectedException.none();

  @Before
  public void setUpInspector() {
    basePath = Paths.get("example", "path");
    marshaller = new ConstructorArgMarshaller(new DefaultTypeCoercerFactory());
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void shouldPopulateAStringValue() throws Exception {
    DtoWithString built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithString.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("string", "cheese"));

    assertEquals("cheese", built.getString());
  }

  @Test
  public void shouldPopulateABooleanValue() throws Exception {
    DtoWithBoolean built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithBoolean.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "booleanOne", true,
                "booleanTwo", true));

    assertTrue(built.getBooleanOne());
    assertTrue(built.isBooleanTwo());
  }

  @Test
  public void shouldPopulateBuildTargetValues() throws Exception {
    DtoWithBuildTargets built =
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
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//cake:walk"), built.getTarget());
    assertEquals(
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example/path:fish"),
        built.getLocal());
  }

  @Test
  public void shouldPopulateANumericValue() throws Exception {
    DtoWithLong built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithLong.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("number", 42L));

    assertEquals(42, built.getNumber());
  }

  @Test
  public void shouldPopulateAPathValue() throws Exception {
    DtoWithPath built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithPath.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("path", "Fish.java"));

    assertEquals(Paths.get("example/path", "Fish.java"), built.getPath());
  }

  @Test
  public void shouldPopulateSourcePaths() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:peas");
    DtoWithSourcePaths built =
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
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithImmutableSortedSet.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "stuff", ImmutableList.of("//please/go:here", ":there")));

    assertEquals(ImmutableSortedSet.of(t2, t1), built.getStuff());
  }

  @Test
  public void shouldPopulateSets() throws Exception {
    DtoWithSetOfPaths built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSetOfPaths.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("paths", ImmutableList.of("one", "two")));

    assertEquals(
        ImmutableSet.of(Paths.get("example/path/one"), Paths.get("example/path/two")),
        built.getPaths());
  }

  @Test
  public void shouldPopulateLists() throws Exception {
    DtoWithListOfStrings built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithListOfStrings.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("list", ImmutableList.of("alpha", "beta")));

    assertEquals(ImmutableList.of("alpha", "beta"), built.getList());
  }

  @Test
  public void onlyFieldNamedDepsAreConsideredDeclaredDeps() throws Exception {
    String dep = "//is/a/declared:dep";
    String notDep = "//is/not/a/declared:dep";

    BuildTarget declaredDep = BuildTargetFactory.newInstance(dep);

    Map<String, Object> args = new HashMap<>();
    args.put("deps", ImmutableList.of(dep));
    args.put("notdeps", ImmutableList.of(notDep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    DtoWithDepsAndNotDeps built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDepsAndNotDeps.class,
            declaredDeps,
            args);

    assertEquals(ImmutableSet.of(declaredDep), declaredDeps.build());
    assertEquals(ImmutableSet.of(declaredDep), built.getDeps());
  }

  @Test
  public void fieldsWithIsDepEqualsFalseHintAreNotTreatedAsDeps() throws Exception {
    String dep = "//should/be:ignored";
    Map<String, Object> args = ImmutableMap.of("deps", ImmutableList.of(dep));

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();

    DtoWithFakeDeps built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithFakeDeps.class,
            declaredDeps,
            args);

    assertEquals(ImmutableSet.of(), declaredDeps.build());
    assertEquals(ImmutableSet.of(BuildTargetFactory.newInstance(dep)), built.getDeps());
  }

  @Test
  public void collectionsAreOptional() throws Exception {
    DtoWithCollections built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithCollections.class,
            ImmutableSet.builder(),
            ImmutableMap.of());

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

    DtoWithOptionalSetOfStrings built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithOptionalSetOfStrings.class,
            ImmutableSet.builder(),
            args);

    assertEquals(Optional.empty(), built.getStrings());
  }

  @Test
  public void errorsOnMissingValues() throws Exception {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(containsString(TARGET.getFullyQualifiedName()));
    expected.expectMessage(containsString("missing required"));
    expected.expectMessage(containsString("booleanOne"));
    expected.expectMessage(containsString("booleanTwo"));

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        DtoWithBoolean.class,
        ImmutableSet.builder(),
        ImmutableMap.of());
  }

  @Test
  public void errorsOnBadChecks() throws Exception {
    expected.expect(RuntimeException.class);
    expected.expectMessage(containsString(TARGET.getFullyQualifiedName()));
    expected.expectMessage(containsString("NOT THE SECRETS"));

    marshaller.populate(
        createCellRoots(filesystem),
        filesystem,
        TARGET,
        DtoWithCheck.class,
        ImmutableSet.builder(),
        ImmutableMap.of("string", "secrets"));
  }

  @Test
  public void noErrorsOnGoodChecks() throws Exception {
    DtoWithCheck built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithCheck.class,
            ImmutableSet.builder(),
            ImmutableMap.of("string", "not secrets"));
    assertEquals("not secrets", built.getString());
  }

  @Test(expected = ParamInfoException.class)
  public void shouldBeAnErrorToAttemptToSetASingleValueToACollection() throws Exception {
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
    DtoWithPath built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithPath.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("path", "./bar/././fish.txt"));

    assertEquals(basePath.resolve("bar/fish.txt").normalize(), built.getPath());
  }

  @Test
  public void shouldSetBuildTargetParameters() throws Exception {
    DtoWithBuildTargetList built =
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

    assertEquals(cheese, built.getSingle());
    assertEquals(cake, built.getSameBuildFileTarget());
    assertEquals(ImmutableList.of(cake, cheese), built.getTargets());
  }

  @Test
  public void specifyingZeroIsNotConsideredOptional() throws Exception {
    DtoWithOptionalInteger built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithOptionalInteger.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("number", 0));
    assertEquals(Optional.of(0), built.getNumber());
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
    DtoWithVariousTypes built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithVariousTypes.class,
            ImmutableSet.builder(),
            args);

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
    DtoWithOptionalValues built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithOptionalValues.class,
            ImmutableSet.builder(),
            args);

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
    DtoWithDefaultValues built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDefaultValues.class,
            ImmutableSet.builder(),
            args);

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
    args.put("more", 1234L);
    args.put("beGood", false);
    DtoWithDefaultValues built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDefaultValues.class,
            ImmutableSet.builder(),
            args);

    assertEquals("bar", built.getSomething());
    assertEquals(ImmutableList.of("qux", "quz"), built.getThings());
    assertEquals(1234, built.getMore());
    assertFalse(built.getBeGood());
  }

  @Test
  public void shouldResolveCollectionOfSourcePathsRelativeToTarget() throws Exception {
    DtoWithSetOfSourcePaths built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithSetOfSourcePaths.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "srcs", ImmutableList.of("main.py", "lib/__init__.py", "lib/manifest.py")));

    ImmutableSet<String> observedValues =
        built
            .getSrcs()
            .stream()
            .map(input -> ((PathSourcePath) input).getRelativePath().toString())
            .collect(ImmutableSet.toImmutableSet());
    assertEquals(
        ImmutableSet.of(
            Paths.get("example/path/main.py").toString(),
            Paths.get("example/path/lib/__init__.py").toString(),
            Paths.get("example/path/lib/manifest.py").toString()),
        observedValues);
  }

  @Test(expected = HumanReadableException.class)
  public void bogusVisibilityGivesFriendlyError() {
    ConstructorArgMarshaller.populateVisibilityPatterns(
        createCellRoots(filesystem), "visibility", ImmutableList.of(":marmosets"), TARGET);
  }

  @Test
  public void derivedMethodsAreIgnored() throws Exception {
    DtoWithDerivedAndOrdinaryMethods built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDerivedAndOrdinaryMethods.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("string", "tamarins"));
    assertEquals("tamarins", built.getString());
    assertEquals("TAMARINS", built.getUpper());
    assertEquals("constant", built.getConstant());
  }

  @Test
  public void specifyingDerivedValuesIsIgnored() throws Exception {
    DtoWithDerivedAndOrdinaryMethods built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            DtoWithDerivedAndOrdinaryMethods.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of(
                "string", "tamarins",
                "upper", "WRONG"));
    assertEquals("tamarins", built.getString());
    assertEquals("TAMARINS", built.getUpper());
  }

  @Test
  public void defaultMethodFallsBackToDefault() throws Exception {
    InheritsFromHasDefaultMethod built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            InheritsFromHasDefaultMethod.class,
            ImmutableSet.builder(),
            ImmutableMap.of());
    assertEquals("foo", built.getString());
  }

  @Test
  public void defaultMethodCanBeSpecified() throws Exception {
    InheritsFromHasDefaultMethod built =
        marshaller.populate(
            createCellRoots(filesystem),
            filesystem,
            TARGET,
            InheritsFromHasDefaultMethod.class,
            ImmutableSet.builder(),
            ImmutableMap.<String, Object>of("string", "bar"));
    assertEquals("bar", built.getString());
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithString {
    abstract String getString();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithBoolean {
    abstract boolean getBooleanOne();

    abstract boolean isBooleanTwo();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithListOfStrings {
    abstract List<String> getList();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithFakeDeps {
    @Hint(isDep = false)
    abstract Set<BuildTarget> getDeps();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithCollections {
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

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithOptionalSetOfStrings {
    abstract Optional<Set<String>> getStrings();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithSetOfStrings {
    abstract Set<String> getStrings();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithPath {
    abstract Path getPath();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithOptionalInteger {
    abstract Optional<Integer> getNumber();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractEmptyImmutableDto {}

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithBuildTargets {
    abstract BuildTarget getTarget();

    abstract BuildTarget getLocal();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithLong {
    abstract long getNumber();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithSourcePaths {
    abstract SourcePath getFilePath();

    abstract SourcePath getTargetPath();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithImmutableSortedSet {
    abstract ImmutableSortedSet<BuildTarget> getStuff();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithSetOfPaths {
    abstract Set<Path> getPaths();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithDepsAndNotDeps {
    abstract Set<BuildTarget> getDeps();

    abstract Set<BuildTarget> getNotDeps();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithBuildTargetList {
    abstract BuildTarget getSingle();

    abstract BuildTarget getSameBuildFileTarget();

    abstract List<BuildTarget> getTargets();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithVariousTypes {
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

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithOptionalValues {
    abstract Optional<String> getNoString();

    abstract Optional<String> getDefaultString();

    abstract Optional<SourcePath> getNoSourcePath();

    abstract Optional<SourcePath> getDefaultSourcePath();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithDefaultValues {
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

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithSetOfSourcePaths {
    abstract ImmutableSortedSet<SourcePath> getSrcs();
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithCheck {
    abstract String getString();

    @Value.Check
    public void check() {
      Preconditions.checkState(!getString().equals("secrets"), "NOT THE SECRETS!");
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDtoWithDerivedAndOrdinaryMethods {
    abstract String getString();

    public String getConstant() {
      return "constant";
    }

    @Value.Derived
    public String getUpper() {
      return getString().toUpperCase();
    }
  }

  interface HasDefaultMethod {
    @Value.Default
    default String getString() {
      return "foo";
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractInheritsFromHasDefaultMethod extends HasDefaultMethod {}
}
