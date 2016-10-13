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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableBiMap;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessorOutputTransformerFactoryTest {
  @Test
  public void shouldRewriteLineMarkers() {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    Path original = Paths.get("buck-out/foo#bar/world.h");
    Path finalPath = Paths.get("SANITIZED/world.h");

    HeaderPathNormalizer.Builder normalizerBuilder =
        new HeaderPathNormalizer.Builder(pathResolver, Functions.identity());
    normalizerBuilder.addHeader(new FakeSourcePath("hello/////world.h"), original);
    HeaderPathNormalizer normalizer = normalizerBuilder.build();

    DebugPathSanitizer sanitizer = new MungingDebugPathSanitizer(
        9,
        File.separatorChar,
        Paths.get("PWD"),
        ImmutableBiMap.of(Paths.get("hello"), Paths.get("SANITIZED")));
    FakeProjectFilesystem fakeProjectFilesystem = new FakeProjectFilesystem();

    CxxPreprocessorOutputTransformerFactory transformer =
        new CxxPreprocessorOutputTransformerFactory(
            fakeProjectFilesystem.getRootPath(),
            normalizer,
            sanitizer);

    // Fixup line marker lines properly.
    assertThat(
        String.format("# 12 \"%s\"", Escaper.escapePathForCIncludeString(finalPath)),
        equalTo(transformer.transformLine(String.format("# 12 \"%s\"", original))));
    assertThat(
        String.format("# 12 \"%s\" 2 1", Escaper.escapePathForCIncludeString(finalPath)),
        equalTo(transformer.transformLine(String.format("# 12 \"%s\" 2 1", original))));

    // test.h isn't in the replacement map, so shouldn't be replaced.
    assertThat(
        "# 4 \"test.h\"",
        equalTo(transformer.transformLine("# 4 \"test.h\"")));

    // Don't modify non-line-marker lines.
    assertThat(
        "int main() {",
        equalTo(transformer.transformLine("int main() {")));
  }
}
