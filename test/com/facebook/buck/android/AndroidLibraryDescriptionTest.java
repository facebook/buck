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

package com.facebook.buck.android;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeExportDependenciesRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public class AndroidLibraryDescriptionTest {

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule transitiveExportedRule =
        resolver.addToIndex(new FakeBuildRule("//:transitive_exported_rule", pathResolver));
    FakeExportDependenciesRule exportedRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule(
                "//:exported_rule",
                pathResolver,
                transitiveExportedRule));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary = AndroidLibraryBuilder.createBuilder(target)
        .addDep(exportingRule.getBuildTarget())
        .build(resolver);

    assertThat(
        javaLibrary.getDeps(),
        Matchers.allOf(
            Matchers.hasItem(exportedRule),
            Matchers.hasItem(transitiveExportedRule)));
  }

  @Test
  public void rulesMatchingDepQueryBecomeFirstOrderDeps() throws Exception {
    // Set up target graph: rule -> lib -> sublib -> bottom
    TargetNode<JavaLibraryDescription.Arg, JavaLibraryDescription> bottomNode =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//:bottom"))
            .build();
    TargetNode<JavaLibraryDescription.Arg, JavaLibraryDescription> sublibNode =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//:sublib"))
            .addDep(bottomNode.getBuildTarget())
            .build();
    TargetNode<JavaLibraryDescription.Arg, JavaLibraryDescription> libNode =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//:lib"))
            .addDep(sublibNode.getBuildTarget())
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        bottomNode,
        libNode,
        sublibNode);
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule bottomRule = resolver.addToIndex(
        new FakeBuildRule(bottomNode.getBuildTarget(), pathResolver));
    FakeBuildRule sublibRule = resolver.addToIndex(
        new FakeBuildRule(
            sublibNode.getBuildTarget(),
            pathResolver,
            ImmutableSortedSet.of(bottomRule)));
    FakeBuildRule libRule = resolver.addToIndex(
        new FakeBuildRule(
            libNode.getBuildTarget(),
            pathResolver,
            ImmutableSortedSet.of(sublibRule)));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");

    BuildRule javaLibrary = AndroidLibraryBuilder.createBuilder(target)
        .addDep(libNode.getBuildTarget())
        .setDepsQuery("filter('.*lib', deps($declared_deps))")
        .build(resolver, targetGraph);

    assertThat(
        javaLibrary.getDeps(),
        Matchers.hasItems(libRule, sublibRule));
    // The bottom rule should be filtered since it does not match the regex
    assertThat(
        javaLibrary.getDeps(),
            Matchers.not(Matchers.hasItem(bottomRule)));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule transitiveExportedRule =
        resolver.addToIndex(new FakeBuildRule("//:transitive_exported_rule", pathResolver));
    FakeExportDependenciesRule exportedRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule(
                "//:exported_rule",
                pathResolver,
                transitiveExportedRule));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary = AndroidLibraryBuilder.createBuilder(target)
        .addProvidedDep(exportingRule.getBuildTarget())
        .build(resolver);

    assertThat(
        javaLibrary.getDeps(),
        Matchers.allOf(
            Matchers.hasItem(exportedRule),
            Matchers.hasItem(transitiveExportedRule)));
  }

  @Test
  public void bootClasspathAppenderAddsLibsFromAndroidPlatformTarget() {
    BuildContext.Builder builder = BuildContext.builder();

    // Set to non-null values.
    builder.setActionGraph(createMock(ActionGraph.class));
    builder.setSourcePathResolver(createMock(SourcePathResolver.class));
    builder.setJavaPackageFinder(createMock(JavaPackageFinder.class));
    builder.setEventBus(BuckEventBusFactory.newInstance());

    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    List<Path> entries = ImmutableList.of(
        Paths.get("add-ons/addon-google_apis-google-15/libs/effects.jar"),
        Paths.get("add-ons/addon-google_apis-google-15/libs/maps.jar"),
        Paths.get("add-ons/addon-google_apis-google-15/libs/usb.jar"));
    expect(androidPlatformTarget.getBootclasspathEntries()).andReturn(entries);

    replay(androidPlatformTarget);

    builder.setAndroidPlatformTargetSupplier(Suppliers.ofInstance(androidPlatformTarget));

    BootClasspathAppender appender = new BootClasspathAppender();

    JavacOptions options = JavacOptions.builder()
        .setSourceLevel("1.7")
        .setTargetLevel("1.7")
        .build();
    JavacOptions updated = appender.amend(options, builder.build());

    assertEquals(
        Optional.of(
            ("add-ons/addon-google_apis-google-15/libs/effects.jar" + File.pathSeparatorChar +
            "add-ons/addon-google_apis-google-15/libs/maps.jar" + File.pathSeparatorChar +
            "add-ons/addon-google_apis-google-15/libs/usb.jar").replace("/", File.separator)),
        updated.getBootclasspath());

    verify(androidPlatformTarget);
  }
}
