/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.cxx.CxxGenruleBuilder;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
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
        rule.getRustLinakbleDeps(RustTestUtils.DEFAULT_PLATFORM),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
  }
}
