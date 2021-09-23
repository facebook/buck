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

package com.facebook.buck.jvm.java;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavacPluginProperties.Type;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class JavacPluginPropertiesTest {
  @Test
  public void transitiveAnnotationProcessorDepsInInputs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    FakeProjectFilesystem fakeProjectFilesystem = new FakeProjectFilesystem();

    PrebuiltJar rawTransitivePrebuiltJarDep =
        new PrebuiltJar(
            BuildTargetFactory.newInstance("//lib:junit-util"),
            fakeProjectFilesystem,
            TestBuildRuleParams.create(),
            new TestActionGraphBuilder().getSourcePathResolver(),
            FakeSourcePath.of("abi-util.jar"),
            /* mavenCoords */ Optional.empty(),
            /* requiredForSourceOnlyAbi */ false,
            /* generateAbi */ true,
            /* neverMarkAsUnusedDependency */ true,
            /* shouldDesugarInterfaceMethodsInPrebuiltJars */ false);
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
            new TestActionGraphBuilder().getSourcePathResolver(),
            FakeSourcePath.of("abi.jar"),
            /* mavenCoords */ Optional.empty(),
            /* requiredForSourceOnlyAbi */ false,
            /* generateAbi */ true,
            /* neverMarkAsUnusedDependency */ true,
            /* shouldDesugarInterfaceMethodsInPrebuiltJars */ false);
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
        graphBuilder.filterBuildRuleInputs(props.getInputs()),
        Matchers.containsInAnyOrder(
            processor, javaLibraryDep, firstLevelPrebuiltJarDep, transitivePrebuiltJarDep));

    assertThat(
        graphBuilder.filterBuildRuleInputs(props.getClasspathEntries()),
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
            resource
                .<ImmutableSortedSet<com.facebook.buck.core.sourcepath.SourcePath>>map(
                    ImmutableSortedSet::of)
                .orElseGet(ImmutableSortedSet::of));
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

    return (JavaAnnotationProcessor)
        new JavaAnnotationProcessorDescription()
            .createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, filesystem),
                BuildTargetFactory.newInstance("//:annotation_processor"),
                params,
                annotationProcessorDescriptionArg);
  }

  private RuleKey createInputRuleKey(Optional<String> resourceName) {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    Optional<PathSourcePath> resource =
        resourceName.map(s -> PathSourcePath.of(filesystem, Paths.get(s)));
    JavaAnnotationProcessor processor =
        createAnnotationProcessor(resource, graphBuilder, filesystem);

    ImmutableMap.Builder<Path, HashCode> builder =
        ImmutableMap.<Path, HashCode>builder()
            .put(
                filesystem
                    .resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem.getBuckPaths(),
                            BuildTargetFactory.newInstance("//:internal_java_lib"),
                            "%s.jar"))
                    .getPath(),
                HashCode.fromInt(0));
    resource.ifPresent(
        pathSourcePath ->
            builder.put(
                graphBuilder.getSourcePathResolver().getAbsolutePath(pathSourcePath).getPath(),
                HashCode.fromInt(resourceName.get().hashCode())));
    FakeFileHashCache hashCache = new FakeFileHashCache(builder.build());

    return new TestInputBasedRuleKeyFactory(hashCache, graphBuilder).build(processor);
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
