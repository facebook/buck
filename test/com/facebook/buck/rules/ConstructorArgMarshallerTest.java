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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.util.ProjectFilesystem;
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

import java.io.File;
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

  @Before
  public void setUpInspector() {
    basePath = Paths.get("example", "path");
    marshaller = new ConstructorArgMarshaller(basePath);
    ruleResolver = new BuildRuleResolver();
  }

  @Test
  public void shouldNotPopulateAnEmptyArg() {
    class Dto implements ConstructorArg {
    }

    Dto dto = new Dto();
    try {
      marshaller.populate(
          ruleResolver, buildRuleFactoryParams(ImmutableMap.<String, Object>of()), dto);
    } catch (RuntimeException e) {
      fail("Did not expect an exception to be thrown:\n" + Throwables.getStackTraceAsString(e));
    }
  }

  @Test
  public void shouldPopulateAStringValue() {
    class Dto implements ConstructorArg {
      public String name;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("name", "cheese")),
        dto);

    assertEquals("cheese", dto.name);
  }

  @Test
  public void shouldPopulateABooleanValue() {
    class Dto implements ConstructorArg {
      public boolean value;
    }

    Dto dto = new Dto();
    marshaller.populate(ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("value", true)),
        dto);

    assertTrue(dto.value);
  }

  @Test
  public void shouldPopulateBuildTargetValues() {
    class Dto implements ConstructorArg {
      public BuildTarget target;
      public BuildTarget local;
    }

    Dto dto = new Dto();
    marshaller.populate(ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "target", "//cake:walk",
            "local", ":fish"
        )),
        dto);

    assertEquals(BuildTargetFactory.newInstance("//cake:walk"), dto.target);
    assertEquals(BuildTargetFactory.newInstance("//example/path:fish"), dto.local);
  }

  @Test
  public void shouldPopulateANumericValue() {
    class Dto implements ConstructorArg {
      public long number;
    }

    Dto dto = new Dto();
    marshaller.populate(ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("number", 42L)),
        dto);

    assertEquals(42, dto.number);
  }

  @Test
  public void shouldPopulateAPathValue() {
    class Dto implements ConstructorArg {
      @Hint(name = "some_path")
      public Path somePath;
    }

    Dto dto = new Dto();
    marshaller.populate(ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("somePath", "Fish.java")),
        dto);

    assertEquals(Paths.get("example/path", "Fish.java"), dto.somePath);
  }

  @Test
  public void shouldPopulateSourcePaths() {
    class Dto implements ConstructorArg {
      public SourcePath filePath;
      public SourcePath targetPath;
    }

    BuildTarget target = BuildTargetFactory.newInstance("//example/path:peas");
    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "filePath", "cheese.txt",
            "targetPath", ":peas"
        )),
        dto);

    assertEquals(new FileSourcePath("example/path/cheese.txt"), dto.filePath);
    assertEquals(new BuildTargetSourcePath(target), dto.targetPath);
  }

  @Test
  public void shouldPopulateAnImmutableSortedSet() {
    class Dto implements ConstructorArg {
      public ImmutableSortedSet<BuildTarget> deps;
    }

    BuildTarget t1 = BuildTargetFactory.newInstance("//please/go:here");
    BuildTarget t2 = BuildTargetFactory.newInstance("//example/path:there");

    Dto dto = new Dto();
    // Note: the ordering is reversed from the natural ordering
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "deps", ImmutableList.of("//please/go:here", ":there"))),
        dto);

    assertEquals(ImmutableSortedSet.of(t2, t1), dto.deps);
  }

  @Test
  public void shouldPopulateSets() {
    class Dto implements ConstructorArg {
      public Set<Path> paths;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "paths", ImmutableList.of("one", "two"))),
        dto);

    assertEquals(
        ImmutableSet.of(Paths.get("example/path/one"), Paths.get("example/path/two")),
        dto.paths);
  }

  @Test
  public void shouldPopulateLists() {
    class Dto implements ConstructorArg {
      public List<String> list;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "list", ImmutableList.of("alpha", "beta"))),
        dto);

    assertEquals(ImmutableList.of("alpha", "beta"), dto.list);
  }

  @Test
  public void collectionsCanBeOptionalAndWillBeSetToAnOptionalEmptyCollectionIfMissing() {
    class Dto implements ConstructorArg {
      public Optional<Set<BuildTarget>> targets;
    }

    Dto dto = new Dto();
    Map<String, Object> args = Maps.newHashMap();
    args.put("targets", Lists.newArrayList());

    marshaller.populate(ruleResolver, buildRuleFactoryParams(args), dto);

    assertEquals(Optional.of(Sets.newHashSet()), dto.targets);
  }

  @Test(expected = RuntimeException.class)
  public void shouldBeAnErrorToAttemptToSetASingleValueToACollection() {
    class Dto implements ConstructorArg {
      public String file;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("file", ImmutableList.of("a", "b"))),
        dto);
  }

  @Test(expected = RuntimeException.class)
  public void shouldBeAnErrorToAttemptToSetACollectionToASingleValue() {
    class Dto implements ConstructorArg {
      public Set<String> strings;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("strings", "isn't going to happen")),
        dto);
  }

  @Test(expected = RuntimeException.class)
  public void shouldBeAnErrorToSetTheWrongTypeOfValueInACollection() {
    class Dto implements ConstructorArg {
      public Set<String> strings;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "strings", ImmutableSet.of(true, false))),
        dto);
  }

  @Test
  public void shouldNormalizePaths() {
    class Dto implements ConstructorArg {
      public Path path;
    }

    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of("path", "./bar/././fish.txt")),
        dto);

    assertEquals(basePath.resolve("bar/fish.txt").normalize(), dto.path);
  }

  @Test(expected = IllegalArgumentException.class)
  public void lowerBoundGenericTypesCauseAnException() {
    class Dto implements ConstructorArg {
      public List<? super BuildTarget> nope;
    }

    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "nope", ImmutableList.of("//will/not:happen"))),
        new Dto());
  }

  public void shouldSetBuildTargetParameters() {
    class Dto implements ConstructorArg {
      public BuildTarget single;
      public BuildTarget sameBuildFileTarget;
      public List<BuildTarget> targets;
    }
    Dto dto = new Dto();

    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "single", "//com/example:cheese",
            "sameBuildFileTarget", ":cake",
            "targets", ImmutableList.of(":cake", "//com/example:cheese")
        )),
        dto);

    BuildTarget cheese = BuildTargetFactory.newInstance("//com/example:cheese");
    BuildTarget cake = BuildTargetFactory.newInstance("//example/path:cake");

    assertEquals(cheese, dto.single);
    assertEquals(cake, dto.sameBuildFileTarget);
    assertEquals(ImmutableList.of(cake, cheese), dto.targets);
  }

  @Test
  public void shouldSetBuildRulesIfRequested() {
    class Dto implements ConstructorArg {
      public BuildRule directDep;
    }

    BuildTarget target = BuildTargetFactory.newInstance("//some/exmaple:target");
    BuildRule rule = new FakeBuildRule(new BuildRuleType("example"), target);

    BuildRuleResolver resolver = new BuildRuleResolver(ImmutableMap.of(target, rule));

    Dto dto = new Dto();
    marshaller.populate(
        resolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "directDep", target.getFullyQualifiedName())),
        dto);

    assertEquals(dto.directDep, rule);
  }

  @Test
  public void upperBoundGenericTypesCauseValuesToBeSetToTheUpperBound() {
    class Dto implements ConstructorArg {
      public List<? extends SourcePath> yup;
    }

    BuildTarget target = BuildTargetFactory.newInstance("//will:happen");
    Dto dto = new Dto();
    marshaller.populate(
        ruleResolver,
        buildRuleFactoryParams(ImmutableMap.<String, Object>of(
            "yup", ImmutableList.of(target.getFullyQualifiedName()))),
        dto);

    BuildTargetSourcePath path = new BuildTargetSourcePath(target);
    assertEquals(ImmutableList.of(path), dto.yup);
  }

  @Test
  public void canPopulateSimpleConstructorArgFromBuildFactoryParams() {
    class Dto implements ConstructorArg {
      public String required;
      public Optional<String> notRequired;

      public int num;
      // Turns out there no optional number params
      //public Optional<Long> optionalLong;

      public boolean needed;
      // Turns out there are no optional boolean params
      //public Optional<Boolean> notNeeded;

      public SourcePath aSrcPath;
      public Optional<SourcePath> notASrcPath;

      public Path aPath;
      public Optional<Path> notAPath;
    }

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
    marshaller.populate(ruleResolver, buildRuleFactoryParams(args), dto);

    assertEquals("cheese", dto.required);
    assertEquals("cake", dto.notRequired.get());
    assertEquals(42, dto.num);
    assertTrue(dto.needed);
    BuildTargetSourcePath expected = new BuildTargetSourcePath(
        BuildTargetFactory.newInstance("//example/path:path"));
    assertEquals(expected, dto.aSrcPath);
    assertEquals(Paths.get("example/path/NotFile.java"), dto.notAPath.get());
  }

  @Test
  /**
   * Since we populated the params from the python script, and the python script inserts default
   * values instead of nulls it's never possible for someone to see "Optional.absent()", but that's
   * what we want as authors of buildables. Handle that case.
   */
  public void shouldPopulateDefaultValuesAsBeingAbsent() {
    class Dto implements ConstructorArg {
      public Optional<String> noString;
      public Optional<String> defaultString;

      public Optional<SourcePath> noSourcePath;
      public Optional<SourcePath> defaultSourcePath;
    }

    ImmutableMap<String, Object> args = ImmutableMap.<String, Object>builder()
        .put("defaultString", "")
        .put("defaultSourcePath", "")
        .build();
    Dto dto = new Dto();
    marshaller.populate(ruleResolver, buildRuleFactoryParams(args), dto);

    assertEquals(Optional.absent(), dto.noString);
    assertEquals(Optional.absent(), dto.defaultString);
    assertEquals(Optional.absent(), dto.noSourcePath);
    assertEquals(Optional.absent(), dto.defaultSourcePath);
  }

  @Test
  public void shouldResolveBuildRulesFromTargetsAndAssignToFields() {
    class Dto implements ConstructorArg {
      public BuildRule rule;
    }

    BuildTarget target = BuildTargetFactory.newInstance("//i/love:lucy");
    BuildRule rule = new FakeBuildRule(new BuildRuleType("example"), target);
    BuildRuleResolver resolver = new BuildRuleResolver(ImmutableMap.of(target, rule));

    Dto dto = new Dto();
    marshaller.populate(
        resolver,
        buildRuleFactoryParams(
            ImmutableMap.<String, Object>of("rule", target.getFullyQualifiedName())),
        dto);

    assertEquals(rule, dto.rule);
  }

  @Test
  public void shouldResolveCollectionOfSourcePaths() {
    class Dto implements ConstructorArg {
      public ImmutableSortedSet<SourcePath> srcs;
    }

    BuildTarget target = BuildTargetFactory.newInstance("//example/path:manifest");
    BuildRule rule = new FakeBuildRule(new BuildRuleType("py"), target);
    BuildRuleResolver resolver = new BuildRuleResolver(ImmutableMap.of(target, rule));

    Dto dto = new Dto();
    marshaller.populate(
        resolver,
        buildRuleFactoryParams(
            ImmutableMap.<String, Object>of("srcs",
                ImmutableList.of("main.py", "lib/__init__.py", "lib/manifest.py"))),
        dto);

    ImmutableSet<String> observedValues = FluentIterable.from(dto.srcs)
        .transform(SourcePath.TO_REFERENCE)
        .toSet();
    assertEquals(
        ImmutableSet.of(
            "example/path/main.py",
            "example/path/lib/__init__.py",
            "example/path/lib/manifest.py"),
        observedValues);
  }

  @Test
  public void shouldBeAbleToSetGenfilesProperly() {
    class Dto implements ConstructorArg {
      public Path path;
      public SourcePath sourcePath;
    }

    String raw = BuildRuleFactoryParams.GENFILE_PREFIX + "Thing.java";

    Dto dto = new Dto();
    BuildRuleFactoryParams params = buildRuleFactoryParams(
        ImmutableMap.<String, Object>of(
            "path", raw,
            "sourcePath", raw));

    Path expected = params.resolveFilePathRelativeToBuildFileDirectory(raw);

    marshaller.populate(
        ruleResolver,
        params,
        dto);

    assertEquals(expected, dto.path);
  }

  public BuildRuleFactoryParams buildRuleFactoryParams(Map<String, Object> args) {
    ProjectFilesystem filesystem = new ProjectFilesystem(new File(".")) {
      @Override
      public boolean exists(String pathRelativeToProjectRoot) {
        return true;
      }
    };
    BuildTargetParser parser = new BuildTargetParser(filesystem);
    BuildTarget target = BuildTargetFactory.newInstance("//example/path:three");
    return NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
        args, parser, target);
  }
}
