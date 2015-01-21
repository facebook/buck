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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused") // Many unused fields in sample DTO objects.
public class ConstructorArgMarshallerTest {

  private Path basePath;
  private ConstructorArgMarshaller marshaller;
  private BuildRuleResolver ruleResolver;
  private ProjectFilesystem filesystem;
  private BuildRuleType ruleType;

  @Before
  public void setUpInspector() {
    basePath = Paths.get("example", "path");
    marshaller = new ConstructorArgMarshaller();
    ruleResolver = new BuildRuleResolver();
    filesystem = new FakeProjectFilesystem();
    ruleType = ImmutableBuildRuleType.of("example");
  }

  @Test
  public void shouldNotPopulateAnEmptyArg() throws NoSuchBuildTargetException {
    class Dto {
    }

    Dto dto = new Dto();
    try {
      marshaller.populate(
          filesystem,
          buildRuleFactoryParams(),
          dto,
          ImmutableSet.<BuildTarget>builder(),
          ImmutableSet.<BuildTargetPattern>builder(),
          ImmutableMap.<String, Object>of());
    } catch (RuntimeException | ConstructorArgMarshalException e) {
      fail("Did not expect an exception to be thrown:\n" + Throwables.getStackTraceAsString(e));
    }
  }

  @Test
  public void shouldPopulateAStringValue()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public String name;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("name", "cheese"));

    assertEquals("cheese", dto.name);
  }

  @Test
  public void shouldPopulateABooleanValue()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public boolean value;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("value", true));

    assertTrue(dto.value);
  }

  @Test
  public void shouldPopulateBuildTargetValues()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public BuildTarget target;
      public BuildTarget local;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of(
            "target", "//cake:walk",
            "local", ":fish"
        ));

    assertEquals(BuildTargetFactory.newInstance("//cake:walk"), dto.target);
    assertEquals(BuildTargetFactory.newInstance("//example/path:fish"), dto.local);
  }

  @Test
  public void shouldPopulateANumericValue()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public long number;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("number", 42L));

    assertEquals(42, dto.number);
  }

  @Test
  public void shouldPopulateAPathValue()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      @Hint(name = "some_path")
      public Path somePath;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("somePath", "Fish.java"));

    assertEquals(Paths.get("example/path", "Fish.java"), dto.somePath);
  }

  @Test
  public void shouldPopulateSourcePaths()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public SourcePath filePath;
      public SourcePath targetPath;
    }

    BuildTarget target = BuildTargetFactory.newInstance("//example/path:peas");
    FakeBuildRule rule = new FakeBuildRule(
        ruleType,
        target,
        new SourcePathResolver(new BuildRuleResolver()));
    ruleResolver.addToIndex(rule);
    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of(
            "filePath", "cheese.txt",
            "targetPath", ":peas"
        ));

    assertEquals(new PathSourcePath(Paths.get("example/path/cheese.txt")), dto.filePath);
    assertEquals(new BuildTargetSourcePath(rule.getBuildTarget()), dto.targetPath);
  }

  @Test
  public void shouldPopulateAnImmutableSortedSet()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public ImmutableSortedSet<BuildTarget> deps;
    }

    BuildTarget t1 = BuildTargetFactory.newInstance("//please/go:here");
    BuildTarget t2 = BuildTargetFactory.newInstance("//example/path:there");

    Dto dto = new Dto();
    // Note: the ordering is reversed from the natural ordering
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("deps", ImmutableList.of("//please/go:here", ":there")));

    assertEquals(ImmutableSortedSet.of(t2, t1), dto.deps);
  }

  @Test
  public void shouldPopulateSets()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public Set<Path> paths;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("paths", ImmutableList.of("one", "two")));

    assertEquals(
        ImmutableSet.of(Paths.get("example/path/one"), Paths.get("example/path/two")),
        dto.paths);
  }

  @Test
  public void shouldPopulateLists()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public List<String> list;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("list", ImmutableList.of("alpha", "beta")));

    assertEquals(ImmutableList.of("alpha", "beta"), dto.list);
  }

  @Test
  public void collectionsCanBeOptionalAndWillBeSetToAnOptionalEmptyCollectionIfMissing()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public Optional<Set<BuildTarget>> targets;
    }

    Dto dto = new Dto();
    Map<String, Object> args = Maps.newHashMap();
    args.put("targets", Lists.newArrayList());

    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        args);

    assertEquals(Optional.of((Set<BuildTarget>) Sets.<BuildTarget>newHashSet()), dto.targets);
  }

  @Test
  public void optionalCollectionsWithoutAValueWillBeSetToAnEmptyOptionalCollection()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public Optional<Set<String>> strings;
    }

    Dto dto = new Dto();
    Map<String, Object> args = Maps.newHashMap();
    // Deliberately not populating args

    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        args);

    assertEquals(Optional.of((Set<String>) Sets.<String>newHashSet()), dto.strings);
  }

  @Test(expected = ConstructorArgMarshalException.class)
  public void shouldBeAnErrorToAttemptToSetASingleValueToACollection()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public String file;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("file", ImmutableList.of("a", "b")));
  }

  @Test(expected = ConstructorArgMarshalException.class)
  public void shouldBeAnErrorToAttemptToSetACollectionToASingleValue()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public Set<String> strings;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("strings", "isn't going to happen"));
  }

  @Test(expected = ConstructorArgMarshalException.class)
  public void shouldBeAnErrorToSetTheWrongTypeOfValueInACollection()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public Set<String> strings;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("strings", ImmutableSet.of(true, false)));
  }

  @Test
  public void shouldNormalizePaths()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public Path path;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("path", "./bar/././fish.txt"));

    assertEquals(basePath.resolve("bar/fish.txt").normalize(), dto.path);
  }

  @Test(expected = RuntimeException.class)
  public void lowerBoundGenericTypesCauseAnException()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public List<? super BuildTarget> nope;
    }

    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        new Dto(),
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("nope", ImmutableList.of("//will/not:happen")));
  }

  public void shouldSetBuildTargetParameters()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public BuildTarget single;
      public BuildTarget sameBuildFileTarget;
      public List<BuildTarget> targets;
    }
    Dto dto = new Dto();

    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
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
  public void upperBoundGenericTypesCauseValuesToBeSetToTheUpperBound()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public List<? extends SourcePath> yup;
    }

    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new FakeBuildRule(
        ImmutableBuildRuleType.of("example"),
        BuildTargetFactory.newInstance("//will:happen"), pathResolver);
    ruleResolver.addToIndex(rule);
    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of(
            "yup",
            ImmutableList.of(rule.getBuildTarget().getFullyQualifiedName())));

    BuildTargetSourcePath path = new BuildTargetSourcePath(rule.getBuildTarget());
    assertEquals(ImmutableList.of(path), dto.yup);
  }

  @Test
  public void specifyingZeroIsNotConsideredOptional()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public Optional<Integer> number;
    }

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of("number", 0));

    assertTrue(dto.number.isPresent());
    assertEquals(Optional.of(0), dto.number);
  }

  @Test
  public void canPopulateSimpleConstructorArgFromBuildFactoryParams()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {

    class Dto {
      public String required;
      public Optional<String> notRequired;

      public int num;
      // Turns out there no optional number params
      //public Optional<Long> optionalLong;

      public boolean needed;
      public Optional<Boolean> notNeeded;

      public SourcePath aSrcPath;
      public Optional<SourcePath> notASrcPath;

      public Path aPath;
      public Optional<Path> notAPath;
    }

    FakeBuildRule expectedRule = new FakeBuildRule(
        ruleType,
        BuildTargetFactory.newInstance("//example/path:path"),
        new SourcePathResolver(new BuildRuleResolver()));
    ruleResolver.addToIndex(expectedRule);

    ImmutableMap<String, Object> args = ImmutableMap.<String, Object>builder()
        .put("required", "cheese")
        .put("notRequired", "cake")
        // Long because that's what comes from python.
        .put("num", 42L)
        // Skipping optional Long.
        .put("needed", true)
        // Skipping optional boolean.
        .put("aSrcPath", ":path")
        .put("aPath", "./File.java")
        .put("notAPath", "./NotFile.java")
        .build();
    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        args);

    assertEquals("cheese", dto.required);
    assertEquals("cake", dto.notRequired.get());
    assertEquals(42, dto.num);
    assertTrue(dto.needed);
    assertEquals(Optional.<Boolean>absent(), dto.notNeeded);
    BuildTargetSourcePath expected = new BuildTargetSourcePath(expectedRule.getBuildTarget());
    assertEquals(expected, dto.aSrcPath);
    assertEquals(Paths.get("example/path/NotFile.java"), dto.notAPath.get());
  }

  @Test
  /**
   * Since we populated the params from the python script, and the python script inserts default
   * values instead of nulls it's never possible for someone to see "Optional.absent()", but that's
   * what we want as authors of buildables. Handle that case.
   */
  public void shouldPopulateDefaultValuesAsBeingAbsent()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public Optional<String> noString;
      public Optional<String> defaultString;

      public Optional<SourcePath> noSourcePath;
      public Optional<SourcePath> defaultSourcePath;

      public int primitiveNum;
      public Integer wrapperObjectNum;
      public boolean primitiveBoolean;
      public Boolean wrapperObjectBoolean;
    }

    // This is not an ImmutableMap so we can test null values.
    Map<String, Object> args = Maps.newHashMap();
    args.put("defaultString", null);
    args.put("defaultSourcePath", null);
    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        args);

    assertEquals(Optional.<String>absent(), dto.noString);
    assertEquals(Optional.<String>absent(), dto.defaultString);
    assertEquals(Optional.<SourcePath>absent(), dto.noSourcePath);
    assertEquals(Optional.<SourcePath>absent(), dto.defaultSourcePath);
    assertEquals(0, dto.primitiveNum);
    assertEquals(Integer.valueOf(0), dto.wrapperObjectNum);
  }

  @Test
  public void shouldResolveCollectionOfSourcePaths()
      throws ConstructorArgMarshalException, NoSuchBuildTargetException {
    class Dto {
      public ImmutableSortedSet<SourcePath> srcs;
    }

    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:manifest");
    BuildRule rule = new FakeBuildRule(
        ImmutableBuildRuleType.of("py"),
        target,
        new SourcePathResolver(resolver));
    resolver.addToIndex(rule);

    Dto dto = new Dto();
    marshaller.populate(
        filesystem,
        buildRuleFactoryParams(),
        dto,
        ImmutableSet.<BuildTarget>builder(),
        ImmutableSet.<BuildTargetPattern>builder(),
        ImmutableMap.<String, Object>of(
            "srcs",
            ImmutableList.of("main.py", "lib/__init__.py", "lib/manifest.py")));

    ImmutableSet<String> observedValues = FluentIterable.from(dto.srcs)
        .transform(Functions.toStringFunction())
        .toSet();
    assertEquals(
        ImmutableSet.of(
            "example/path/main.py",
            "example/path/lib/__init__.py",
            "example/path/lib/manifest.py"),
        observedValues);
  }

  public BuildRuleFactoryParams buildRuleFactoryParams() {
    BuildTargetParser parser = new BuildTargetParser();
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:three");
    return NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
        parser,
        target);
  }
}
