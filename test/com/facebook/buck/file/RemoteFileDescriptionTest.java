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

package com.facebook.buck.file;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RemoteFileDescriptionTest {

  @Rule
  public ExpectedException exception = ExpectedException.none();

  private Downloader downloader;
  private RemoteFileDescription description;
  private ProjectFilesystem filesystem;
  private BuildRuleResolver ruleResolver;

  @Before
  public void setUp() {
    downloader = new ExplodingDownloader();
    description = new RemoteFileDescription(downloader);
    filesystem = new FakeProjectFilesystem();
    ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Test
  public void badSha1HasUseableException() throws NoSuchBuildTargetException {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:cake");

    RemoteFileDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.sha1 = "";

    exception.expect(HumanReadableException.class);
    exception.expectMessage(Matchers.containsString(target.getFullyQualifiedName()));

    description.createBuildRule(
        TargetGraph.EMPTY,
        RemoteFileBuilder.createBuilder(downloader, target)
            .createBuildRuleParams(ruleResolver, filesystem),
        ruleResolver,
        arg);
  }
}
