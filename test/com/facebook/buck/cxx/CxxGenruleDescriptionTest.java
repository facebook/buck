/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxGenruleDescriptionTest {

  @Test
  public void toolPlatformParseTimeDeps() {
    for (String macro : ImmutableSet.of("ld", "cc", "cxx")) {
      CxxGenruleBuilder builder =
          new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:rule#default"))
              .setCmd(String.format("$(%s)", macro));
      assertThat(
          ImmutableSet.copyOf(builder.findImplicitDeps()),
          Matchers.equalTo(
              ImmutableSet.copyOf(
                  CxxPlatforms.getParseTimeDeps(CxxPlatformUtils.DEFAULT_PLATFORM))));
    }
  }

  @Test
  public void ldFlagsFilter() throws Exception {
    for (Linker.LinkableDepType style : Linker.LinkableDepType.values()) {
      CxxLibraryBuilder bBuilder =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:b"))
              .setExportedLinkerFlags(ImmutableList.of("-b"));
      CxxLibraryBuilder aBuilder =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:a"))
              .setExportedDeps(ImmutableSortedSet.of(bBuilder.getTarget()))
              .setExportedLinkerFlags(ImmutableList.of("-a"));
      CxxGenruleBuilder builder =
          new CxxGenruleBuilder(
              BuildTargetFactory.newInstance(
                  "//:rule#" + CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()))
              .setOut("out")
              .setCmd(
                  String.format(
                      "$(ldflags-%s-filter .*//:a.* //:a)",
                      CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, style.toString())));
      TargetGraph targetGraph =
          TargetGraphFactory.newInstance(
              bBuilder.build(),
              aBuilder.build(),
              builder.build());
      BuildRuleResolver resolver =
          new BuildRuleResolver(
              targetGraph,
              new DefaultTargetNodeToBuildRuleTransformer());
      bBuilder.build(resolver);
      aBuilder.build(resolver);
      Genrule genrule = (Genrule) builder.build(resolver);
      assertThat(
          Joiner.on(' ').join(Arg.stringify(ImmutableList.of(genrule.getCmd().get()))),
          Matchers.containsString("-a"));
      assertThat(
          Joiner.on(' ').join(Arg.stringify(ImmutableList.of(genrule.getCmd().get()))),
          Matchers.not(Matchers.containsString("-b")));
    }
  }

}
