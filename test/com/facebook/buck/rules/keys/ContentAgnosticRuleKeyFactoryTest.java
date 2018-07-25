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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ContentAgnosticRuleKeyFactoryTest {

  @Test
  public void ruleKeyDoesNotChangeWhenFileContentsChange() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Changing the contents of a file should not alter the rulekey.
    RuleKey ruleKey1 = createRuleKey(filesystem, "output_file", "contents");
    RuleKey ruleKey2 = createRuleKey(filesystem, "output_file", "diff_contents");

    assertThat(ruleKey1, Matchers.equalTo(ruleKey2));
  }

  @Test
  public void ruleKeyDoesChangeWithFileRename() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // A file rename should alter the rulekey.
    RuleKey ruleKey1 = createRuleKey(filesystem, "output_file", "contents");
    RuleKey ruleKey2 = createRuleKey(filesystem, "output_file_2", "diff_contents");

    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }

  private RuleKey createRuleKey(ProjectFilesystem fileSystem, String filename, String fileContents)
      throws Exception {
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    Path depOutput = Paths.get(filename);
    FakeBuildRule dep =
        graphBuilder.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), fileSystem));
    dep.setOutputFile(depOutput.toString());
    fileSystem.writeContentsToPath(
        fileContents, pathResolver.getRelativePath(dep.getSourcePathToOutput()));

    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut(filename)
            .setSrcs(ImmutableList.of(dep.getSourcePathToOutput()))
            .build(graphBuilder, fileSystem);

    return new ContentAgnosticRuleKeyFactory(
            fieldLoader, pathResolver, ruleFinder, Optional.empty())
        .build(rule);
  }
}
