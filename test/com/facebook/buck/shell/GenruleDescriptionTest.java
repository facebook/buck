/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ConstructorArgMarshalException;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.VisibilityPattern;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Map;

public class GenruleDescriptionTest {

  @Test
  public void testImplicitDepsAreAddedCorrectly() throws NoSuchBuildTargetException {
    Description<GenruleDescription.Arg> genruleDescription = new GenruleDescription();
    Map<String, Object> instance = ImmutableMap.of(
        "srcs", ImmutableList.of(":baz", "//biz:baz"),
        "out", "AndroidManifest.xml",
        "cmd", "$(exe //bin:executable) $(location :arg)");
    ProjectFilesystem projectFilesystem = new AllExistingProjectFilesystem();
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        projectFilesystem,
        BuildTargetFactory.newInstance("//foo:bar"));
    ConstructorArgMarshaller marshaller =
        new ConstructorArgMarshaller(new DefaultTypeCoercerFactory(
            ObjectMappers.newDefaultInstance()));
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> visibilityPatterns = ImmutableSet.builder();
    GenruleDescription.Arg constructorArg = genruleDescription.createUnpopulatedConstructorArg();
    try {
      marshaller.populate(
          createCellRoots(projectFilesystem),
          projectFilesystem,
          params,
          constructorArg,
          declaredDeps,
          visibilityPatterns,
          instance);
    }  catch (ConstructorArgMarshalException e) {
      fail("Expected constructorArg to be correctly populated.");
    }
    TargetNode<GenruleDescription.Arg> targetNode =
        new TargetNodeFactory(new DefaultTypeCoercerFactory(ObjectMappers.newDefaultInstance()))
            .create(
                Hashing.sha1().hashString(params.target.getFullyQualifiedName(), UTF_8),
                genruleDescription,
                constructorArg,
                params,
                declaredDeps.build(),
                visibilityPatterns.build(),
                createCellRoots(projectFilesystem));
    assertEquals(
        "SourcePaths and targets from cmd string should be extracted as extra deps.",
        ImmutableSet.of(
            "//foo:baz",
            "//biz:baz",
            "//bin:executable",
            "//foo:arg"),
        targetNode.getExtraDeps().stream()
            .map(Object::toString)
            .collect(MoreCollectors.toImmutableSet()));
  }

  @Test
  public void testClasspathTransitiveDepsBecomeFirstOrderDeps() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule transitiveDep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build(ruleResolver);
    BuildRule dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDep.getBuildTarget())
            .build(ruleResolver);
    Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setCmd("$(classpath //exciting:target)")
            .build(ruleResolver);
    assertThat(genrule.getDeps(), Matchers.containsInAnyOrder(dep, transitiveDep));
  }


}
