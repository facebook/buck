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
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class AndroidLibraryDescriptionTest extends AbiCompilationModeTest {

  private JavaBuckConfig javaBuckConfig;

  @Before
  public void setUp() {
    javaBuckConfig = getJavaBuckConfigWithCompilationMode();
  }

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() throws Exception {
    TargetNode<?, ?> transitiveExportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:transitive_exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/transitive/hi.java"))
            .build();
    TargetNode<?, ?> exportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exported_rule/foo.java"))
            .addExportedDep(transitiveExportedNode.getBuildTarget())
            .build();
    TargetNode<?, ?> exportingNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exporting_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exporting_rule/bar.java"))
            .addExportedDep(exportedNode.getBuildTarget())
            .build();
    TargetNode<?, ?> androidLibNode =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:rule"), javaBuckConfig)
            .addDep(exportingNode.getBuildTarget())
            .build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveExportedNode, exportedNode, exportingNode, androidLibNode);

    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule androidLibRule = resolver.requireRule(androidLibNode.getBuildTarget());
    BuildRule exportedRule = resolver.requireRule(exportedNode.getBuildTarget());
    BuildRule transitiveExportedRule =
        resolver.requireRule(transitiveExportedNode.getBuildTarget());

    // First order deps should become CalculateAbi rules if we're compiling against ABIs
    if (compileAgainstAbis.equals(TRUE)) {
      exportedRule = resolver.getRule(((JavaLibrary) exportedRule).getAbiJar().get());
      transitiveExportedRule =
          resolver.getRule(((JavaLibrary) transitiveExportedRule).getAbiJar().get());
    }

    assertThat(
        androidLibRule.getBuildDeps(),
        Matchers.allOf(Matchers.hasItem(exportedRule), Matchers.hasItem(transitiveExportedRule)));
  }

  @Test
  public void rulesMatchingDepQueryBecomeFirstOrderDeps() throws Exception {
    // Set up target graph: rule -> lib -> sublib -> bottom
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> bottomNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:bottom"), javaBuckConfig)
            .build();
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> sublibNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:sublib"), javaBuckConfig)
            .addDep(bottomNode.getBuildTarget())
            .build();
    TargetNode<JavaLibraryDescriptionArg, JavaLibraryDescription> libNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"), javaBuckConfig)
            .addDep(sublibNode.getBuildTarget())
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(bottomNode, libNode, sublibNode);
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    FakeBuildRule bottomRule =
        resolver.addToIndex(new FakeBuildRule(bottomNode.getBuildTarget(), pathResolver));
    FakeBuildRule sublibRule =
        resolver.addToIndex(
            new FakeBuildRule(
                sublibNode.getBuildTarget(), pathResolver, ImmutableSortedSet.of(bottomRule)));
    FakeBuildRule libRule =
        resolver.addToIndex(
            new FakeBuildRule(
                libNode.getBuildTarget(), pathResolver, ImmutableSortedSet.of(sublibRule)));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");

    BuildRule javaLibrary =
        AndroidLibraryBuilder.createBuilder(target, javaBuckConfig)
            .addDep(libNode.getBuildTarget())
            .setDepsQuery(Query.of("filter('.*lib', deps($declared_deps))"))
            .build(resolver, targetGraph);

    assertThat(javaLibrary.getBuildDeps(), Matchers.hasItems(libRule, sublibRule));
    // The bottom rule should be filtered since it does not match the regex
    assertThat(javaLibrary.getBuildDeps(), Matchers.not(Matchers.hasItem(bottomRule)));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() throws Exception {
    TargetNode<?, ?> transitiveExportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:transitive_exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/transitive/hi.java"))
            .build();
    TargetNode<?, ?> exportedNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exported_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exported_rule/foo.java"))
            .addExportedDep(transitiveExportedNode.getBuildTarget())
            .build();
    TargetNode<?, ?> exportingNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:exporting_rule"), javaBuckConfig)
            .addSrc(Paths.get("java/src/com/exporting_rule/bar.java"))
            .addExportedDep(exportedNode.getBuildTarget())
            .build();
    TargetNode<?, ?> androidLibNode =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:rule"), javaBuckConfig)
            .addProvidedDep(exportingNode.getBuildTarget())
            .build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveExportedNode, exportedNode, exportingNode, androidLibNode);

    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule androidLibRule = resolver.requireRule(androidLibNode.getBuildTarget());
    BuildRule exportedRule = resolver.requireRule(exportedNode.getBuildTarget());
    BuildRule transitiveExportedRule =
        resolver.requireRule(transitiveExportedNode.getBuildTarget());

    // First order deps should become CalculateAbi rules if we're compiling against ABIs
    if (compileAgainstAbis.equals(TRUE)) {
      exportedRule = resolver.getRule(((JavaLibrary) exportedRule).getAbiJar().get());
      transitiveExportedRule =
          resolver.getRule(((JavaLibrary) transitiveExportedRule).getAbiJar().get());
    }

    assertThat(
        androidLibRule.getBuildDeps(),
        Matchers.allOf(Matchers.hasItem(exportedRule), Matchers.hasItem(transitiveExportedRule)));
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
    List<Path> entries =
        ImmutableList.of(
            Paths.get("add-ons/addon-google_apis-google-15/libs/effects.jar"),
            Paths.get("add-ons/addon-google_apis-google-15/libs/maps.jar"),
            Paths.get("add-ons/addon-google_apis-google-15/libs/usb.jar"));
    expect(androidPlatformTarget.getBootclasspathEntries()).andReturn(entries);

    replay(androidPlatformTarget);

    builder.setAndroidPlatformTargetSupplier(Suppliers.ofInstance(androidPlatformTarget));

    BootClasspathAppender appender = new BootClasspathAppender();

    JavacOptions options =
        JavacOptions.builder().setSourceLevel("1.7").setTargetLevel("1.7").build();
    JavacOptions updated = appender.amend(options, builder.build());

    assertEquals(
        Optional.of(
            ("add-ons/addon-google_apis-google-15/libs/effects.jar"
                    + File.pathSeparatorChar
                    + "add-ons/addon-google_apis-google-15/libs/maps.jar"
                    + File.pathSeparatorChar
                    + "add-ons/addon-google_apis-google-15/libs/usb.jar")
                .replace("/", File.separator)),
        updated.getBootclasspath());

    verify(androidPlatformTarget);
  }

  @Test
  public void testClasspathContainsOnlyJavaTargets() throws Exception {
    TargetNode<AndroidResourceDescriptionArg, AndroidResourceDescription> resourceRule =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//:res")).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(resourceRule);

    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    resolver.addToIndex(new FakeBuildRule(resourceRule.getBuildTarget(), pathResolver));

    AndroidLibrary androidLibrary =
        AndroidLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:android_lib"))
            .addDep(resourceRule.getBuildTarget())
            .build(resolver, targetGraph);

    assertThat(androidLibrary.getCompileTimeClasspathSourcePaths(), Matchers.empty());
  }
}
