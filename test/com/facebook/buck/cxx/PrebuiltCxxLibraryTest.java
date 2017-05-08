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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertNotNull;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class PrebuiltCxxLibraryTest {

  @Test
  public void testGetNativeLinkWithDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    GenruleBuilder genSrcBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_libx"))
            .setOut("gen_libx")
            .setCmd("something");
    BuildTarget target = BuildTargetFactory.newInstance("//:x");
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(target).setLibName("x").setLibDir("$(location //:gen_libx)");

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genSrcBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    BuildRule genSrc = genSrcBuilder.build(resolver, filesystem, targetGraph);
    filesystem.writeContentsToPath(
        "class Test {}",
        pathResolver.getAbsolutePath(genSrc.getSourcePathToOutput()).resolve("libx.a"));

    PrebuiltCxxLibrary lib = (PrebuiltCxxLibrary) builder.build(resolver, filesystem, targetGraph);
    lib.getNativeLinkableInput(platform, Linker.LinkableDepType.STATIC);

    FileHashCache originalHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, originalHashCache, pathResolver, ruleFinder);

    RuleKey ruleKey = factory.build(lib);
    assertNotNull(ruleKey);
  }
}
