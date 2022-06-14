/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.rust;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.cxx.CxxGenruleBuilder;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RustLibraryDescriptionTest {

  @Test
  public void testGeneratedSourceFromCxxGenrule() throws NoSuchBuildTargetException {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("lib.rs");
    RustLibraryBuilder libraryBuilder =
        RustLibraryBuilder.from("//:lib")
            .setSrcs(
                ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(srcBuilder.getTarget())));
    RustBinaryBuilder binaryBuilder =
        RustBinaryBuilder.from("//:bin")
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("main.rs")))
            .setDeps(ImmutableSortedSet.of(libraryBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            srcBuilder.build(), libraryBuilder.build(), binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    graphBuilder.requireRule(binaryBuilder.getTarget());
  }

  @Test
  public void platformDeps() {
    RustLibraryBuilder depABuilder = RustLibraryBuilder.from("//:depA");
    RustLibraryBuilder depBBuilder = RustLibraryBuilder.from("//:depB");
    RustLibraryBuilder ruleBuilder =
        RustLibraryBuilder.from("//:rule")
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            RustTestUtils.DEFAULT_PLATFORM.getFlavor().toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(depABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(depBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            depABuilder.build(), depBBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    RustLibrary depA = (RustLibrary) graphBuilder.requireRule(depABuilder.getTarget());
    RustLibrary depB = (RustLibrary) graphBuilder.requireRule(depBBuilder.getTarget());
    RustLibrary rule = (RustLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    assertThat(
        rule.getRustLinkableDeps(RustTestUtils.DEFAULT_PLATFORM),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
  }

  @Test
  public void pluginPlatform() {
    RustLibraryBuilder procMacroBuilder =
        RustLibraryBuilder.from("//:proc-macro")
            .setProcMacro(true)
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("lib.rs")));
    RustLibraryBuilder libBuilder =
        RustLibraryBuilder.from("//:lib#default,rlib")
            .setDeps(ImmutableSortedSet.of(procMacroBuilder.getTarget()))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("lib.rs")));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(procMacroBuilder.build(), libBuilder.build());

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    RustCompileRule rule = (RustCompileRule) graphBuilder.requireRule(libBuilder.getTarget());

    assertThat(
        RichStream.from(rule.getBuildDeps())
            .filter(RustCompileRule.class::isInstance)
            .map(dep -> ((RustCompileRule) dep).getRustPlatform().getFlavor())
            .toImmutableSet(),
        Matchers.hasItems(RustTestUtils.PLUGIN_FLAVOR));
  }

  @Test
  public void androidPackageableGetRequiredPackageables() {
    RustLibraryBuilder depBuilder = RustLibraryBuilder.from("//:dep");
    RustLibraryBuilder ruleBuilder =
        RustLibraryBuilder.from("//:rule").setDeps(ImmutableSortedSet.of(depBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(ruleBuilder.build(), depBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    RustLibrary rule = (RustLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());

    Iterable<AndroidPackageable> packageables =
        rule.getRequiredPackageables(graphBuilder, Suppliers.ofInstance(ImmutableList.of()));
    assertEquals(
        ImmutableSet.copyOf(packageables),
        ImmutableSet.of(graphBuilder.getRule(depBuilder.getTarget())));
  }

  @Test
  public void androidPackageableAddToCollector() {
    RustLibraryBuilder ruleBuilder = RustLibraryBuilder.from("//:rule");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    RustLibrary rule = (RustLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    BuildTarget target = ruleBuilder.getTarget();

    AndroidPackageableCollector collector = new AndroidPackageableCollector(target);
    rule.addToCollector(graphBuilder, collector);

    AndroidPackageableCollection collection = collector.build();
    NativeLinkableGroup[] nativeLinkablesAssets =
        collection.getNativeLinkablesAssets().values().toArray(NativeLinkableGroup[]::new);

    assertEquals(nativeLinkablesAssets.length, 1);
    assertEquals(nativeLinkablesAssets[0], (NativeLinkableGroup) rule);
  }
}
