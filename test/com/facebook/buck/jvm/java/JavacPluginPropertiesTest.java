/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.AbstractJavacPluginProperties.Type;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class JavacPluginPropertiesTest {
  @Test
  public void transitiveAnnotationProcessorDepsInInputs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    FakeProjectFilesystem fakeProjectFilesystem = new FakeProjectFilesystem();

    PrebuiltJar rawTransitivePrebuiltJarDep =
        new PrebuiltJar(
            BuildTargetFactory.newInstance("//lib:junit-util"),
            fakeProjectFilesystem,
            TestBuildRuleParams.create(),
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder())),
            FakeSourcePath.of("abi-util.jar"),
            Optional.of(FakeSourcePath.of("lib/junit-util-4.11-sources.jar")),
            /* gwtJar */ Optional.empty(),
            Optional.of("http://junit-team.github.io/junit-util/javadoc/latest/"),
            /* mavenCoords */ Optional.empty(),
            /* provided */ false,
            /* requiredForSourceOnlyAbi */ false);
    BuildRule transitivePrebuiltJarDep =
        graphBuilder.computeIfAbsent(
            rawTransitivePrebuiltJarDep.getBuildTarget(),
            buildTarget -> rawTransitivePrebuiltJarDep);

    PrebuiltJar rawFirstLevelPrebuiltJarDep =
        new PrebuiltJar(
            BuildTargetFactory.newInstance("//lib:junit"),
            fakeProjectFilesystem,
            new BuildRuleParams(
                () -> ImmutableSortedSet.of(transitivePrebuiltJarDep),
                ImmutableSortedSet::of,
                ImmutableSortedSet.of()),
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder())),
            FakeSourcePath.of("abi.jar"),
            Optional.of(FakeSourcePath.of("lib/junit-4.11-sources.jar")),
            /* gwtJar */ Optional.empty(),
            Optional.of("http://junit-team.github.io/junit/javadoc/latest/"),
            /* mavenCoords */ Optional.empty(),
            /* provided */ false,
            /* requiredForSourceOnlyAbi */ false);
    BuildRule firstLevelPrebuiltJarDep =
        graphBuilder.computeIfAbsent(
            rawFirstLevelPrebuiltJarDep.getBuildTarget(),
            buildTarget -> rawFirstLevelPrebuiltJarDep);

    FakeJavaLibrary rawJavaLibraryDep =
        new FakeJavaLibrary(BuildTargetFactory.newInstance("//:dep"));
    BuildRule javaLibraryDep =
        graphBuilder.computeIfAbsent(
            rawJavaLibraryDep.getBuildTarget(), buildTarget -> rawJavaLibraryDep);

    FakeJavaLibrary rawProcessor =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance("//:processor"),
            ImmutableSortedSet.of(javaLibraryDep, firstLevelPrebuiltJarDep));
    BuildRule processor =
        graphBuilder.computeIfAbsent(rawProcessor.getBuildTarget(), buildTarget -> rawProcessor);

    JavacPluginProperties props =
        JavacPluginProperties.builder()
            .setType(Type.ANNOTATION_PROCESSOR)
            .setCanReuseClassLoader(false)
            .setDoesNotAffectAbi(false)
            .setSupportsAbiGenerationFromSource(false)
            .addDep(processor)
            .build();

    assertThat(
        ruleFinder.filterBuildRuleInputs(props.getInputs()),
        Matchers.containsInAnyOrder(
            processor, javaLibraryDep, firstLevelPrebuiltJarDep, transitivePrebuiltJarDep));

    assertThat(
        ruleFinder.filterBuildRuleInputs(props.getClasspathEntries()),
        Matchers.containsInAnyOrder(
            processor, javaLibraryDep, firstLevelPrebuiltJarDep, transitivePrebuiltJarDep));
  }

  private JavaAnnotationProcessor createAnnotationProcessor(
      Optional<PathSourcePath> resource,
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem filesystem) {

    FakeJavaLibrary javaLibrary =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance("//:internal_java_lib"),
            ImmutableSortedSet.of(),
            resource.isPresent() ? ImmutableSortedSet.of(resource.get()) : ImmutableSortedSet.of());
    graphBuilder.computeIfAbsent(javaLibrary.getBuildTarget(), buildTarget -> javaLibrary);

    JavaAnnotationProcessorDescriptionArg annotationProcessorDescriptionArg =
        JavaAnnotationProcessorDescriptionArg.builder()
            .setName("super_processor")
            .setIsolateClassLoader(false)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .setProcessorClass(Optional.of("Foo.Bar"))
            .addDeps(javaLibrary.getBuildTarget())
            .build();

    BuildRuleParams params =
        TestBuildRuleParams.create()
            .withDeclaredDeps(
                graphBuilder.getAllRules(annotationProcessorDescriptionArg.getDeps()));

    JavaAnnotationProcessor javaAnnotationProcessor =
        (JavaAnnotationProcessor)
            new JavaAnnotationProcessorDescription()
                .createBuildRule(
                    TestBuildRuleCreationContextFactory.create(graphBuilder, filesystem),
                    BuildTargetFactory.newInstance("//:annotation_processor"),
                    params,
                    annotationProcessorDescriptionArg);

    return javaAnnotationProcessor;
  }

  private RuleKey createInputRuleKey(Optional<String> resourceName) {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    Optional<PathSourcePath> resource =
        resourceName.isPresent()
            ? Optional.of(PathSourcePath.of(filesystem, Paths.get(resourceName.get())))
            : Optional.empty();
    JavaAnnotationProcessor processor =
        createAnnotationProcessor(resource, graphBuilder, filesystem);

    ImmutableMap.Builder builder =
        ImmutableMap.builder()
            .put(
                filesystem.resolve(Paths.get("buck-out/gen/internal_java_lib.jar")),
                HashCode.fromInt(0));
    if (resource.isPresent()) {
      builder.put(
          pathResolver.getAbsolutePath(resource.get()),
          HashCode.fromInt(resourceName.get().hashCode()));
    }
    FakeFileHashCache hashCache = new FakeFileHashCache(builder.build());

    return new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(processor);
  }

  @Test
  public void inputBasedRuleKeysChangeIfAnnotationProcessorResourcesChange() {
    Assert.assertNotEquals(
        createInputRuleKey(Optional.of("nice_resource_for_java_lib")),
        createInputRuleKey(Optional.empty()));

    Assert.assertNotEquals(
        createInputRuleKey(Optional.of("original_resource")),
        createInputRuleKey(Optional.of("different_resource")));

    Assert.assertEquals(
        createInputRuleKey(Optional.of("same_resource")),
        createInputRuleKey(Optional.of("same_resource")));
  }
}
