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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JavaAnnotationProcessorDescriptionTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testProcessorClassIsPassedToJavaAnnotationProcessor() {
    // Given
    JavaAnnotationProcessorDescriptionArg arg =
        JavaAnnotationProcessorDescriptionArg.builder()
            .setName("annotation_processor")
            .setIsolateClassLoader(false)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .setProcessorClass(Optional.of("Foo.Bar"))
            .build();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:baz");

    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(graphBuilder.getAllRules(arg.getDeps()));

    // When
    JavaAnnotationProcessor javaAnnotationProcessor =
        (JavaAnnotationProcessor)
            new JavaAnnotationProcessorDescription()
                .createBuildRule(
                    TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                    buildTarget,
                    params,
                    arg);

    // Verify
    JavacPluginProperties props =
        JavacPluginProperties.builder()
            .setCanReuseClassLoader(true)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .addProcessorNames("Foo.Bar")
            .build();

    assertEquals(javaAnnotationProcessor.getUnresolvedProperties(), props);
  }

  @Test
  public void testProcessorClassesIsPassedToJavaAnnotationProcessor() {
    // Given
    JavaAnnotationProcessorDescriptionArg arg =
        JavaAnnotationProcessorDescriptionArg.builder()
            .setName("annotation_processor")
            .setIsolateClassLoader(false)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .setProcessorClasses(ImmutableSet.of("Foo.Bar", "Bar.Foo"))
            .build();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:baz");

    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(graphBuilder.getAllRules(arg.getDeps()));

    // When
    JavaAnnotationProcessor javaAnnotationProcessor =
        (JavaAnnotationProcessor)
            new JavaAnnotationProcessorDescription()
                .createBuildRule(
                    TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                    buildTarget,
                    params,
                    arg);

    // Verify
    JavacPluginProperties props =
        JavacPluginProperties.builder()
            .setCanReuseClassLoader(true)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .addProcessorNames("Foo.Bar", "Bar.Foo")
            .build();

    assertEquals(javaAnnotationProcessor.getUnresolvedProperties(), props);
  }

  @Test
  public void testOnlyProcessorClassIsPassedToJavaAnnotationProcessor() {
    // Given
    JavaAnnotationProcessorDescriptionArg arg =
        JavaAnnotationProcessorDescriptionArg.builder()
            .setName("annotation_processor")
            .setIsolateClassLoader(false)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .setProcessorClass(Optional.of("Needle.HayStack"))
            .setProcessorClasses(ImmutableSet.of("Foo.Bar", "Bar.Foo"))
            .build();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:baz");

    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(graphBuilder.getAllRules(arg.getDeps()));

    // When
    JavaAnnotationProcessor javaAnnotationProcessor =
        (JavaAnnotationProcessor)
            new JavaAnnotationProcessorDescription()
                .createBuildRule(
                    TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                    buildTarget,
                    params,
                    arg);

    // Verify
    JavacPluginProperties props =
        JavacPluginProperties.builder()
            .setCanReuseClassLoader(true)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .addProcessorNames("Needle.HayStack")
            .build();

    assertEquals(javaAnnotationProcessor.getUnresolvedProperties(), props);
  }

  @Test
  public void testRaisesExceptionWhenNoProcessorClassIsSpecified() {
    // Given
    JavaAnnotationProcessorDescriptionArg arg =
        JavaAnnotationProcessorDescriptionArg.builder()
            .setName("annotation_processor")
            .setIsolateClassLoader(false)
            .setDoesNotAffectAbi(true)
            .setSupportsAbiGenerationFromSource(true)
            .build();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:baz");

    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(graphBuilder.getAllRules(arg.getDeps()));

    // Verify
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("//foo:baz: must specify a processor class, none specified;");

    // When
    new JavaAnnotationProcessorDescription()
        .createBuildRule(
            TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
            buildTarget,
            params,
            arg);
  }
}
