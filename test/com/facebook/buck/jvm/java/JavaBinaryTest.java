/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class JavaBinaryTest {

  private static final Path PATH_TO_GUAVA_JAR = Paths.get("third_party/guava/guava-10.0.1.jar");
  private static final Path PATH_TO_GENERATOR_JAR = Paths.get("third_party/guava/generator.jar");

  @Test
  public void testGetExecutableCommand() throws Exception {
    // prebuilt_jar //third_party/generator:generator
    PrebuiltJarBuilder.createBuilder(
            BuildTargetFactory.newInstance("//third_party/generator:generator"))
        .setBinaryJar(PATH_TO_GENERATOR_JAR)
        .build();

    // prebuilt_jar //third_party/guava:guava
    TargetNode<?, ?> guavaNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/guava:guava"))
            .setBinaryJar(PATH_TO_GUAVA_JAR)
            .build();

    // java_library //java/com/facebook/base:base
    TargetNode<?, ?> libraryNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/base:base"))
            .addSrc(Paths.get("java/com/facebook/base/Base.java"))
            .addDep(guavaNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(guavaNode, libraryNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    BuildRule libraryRule = ruleResolver.requireRule(libraryNode.getBuildTarget());

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/base:Main"))
            .setDeclaredDeps(ImmutableSortedSet.of(libraryRule))
            .build();
    // java_binary //java/com/facebook/base:Main
    JavaBinary javaBinary =
        ruleResolver.addToIndex(
            new JavaBinary(
                params,
                new ExternalJavaRuntimeLauncher("/foobar/java"),
                "com.facebook.base.Main",
                null,
                /* merge manifests */ true,
                null,
                /* blacklist */ ImmutableSet.of(),
                ImmutableSet.of(),
                ImmutableSet.of(),
                /* cache */ true));

    // Strip the trailing "." from the absolute path to the current directory.
    final String basePath = new File(".").getAbsolutePath().replaceFirst("\\.$", "");

    // Each classpath entry is specified via its absolute path so that the executable command can be
    // run from a /tmp directory, if necessary.
    String expectedClasspath =
        basePath + pathResolver.getRelativePath(javaBinary.getSourcePathToOutput());

    List<String> expectedCommand = ImmutableList.of("/foobar/java", "-jar", expectedClasspath);
    assertEquals(expectedCommand, javaBinary.getExecutableCommand().getCommandPrefix(pathResolver));

    assertFalse(
        "Library rules that are used exclusively by genrules should not be part of the classpath.",
        expectedClasspath.contains(PATH_TO_GENERATOR_JAR.toString()));
  }
}
