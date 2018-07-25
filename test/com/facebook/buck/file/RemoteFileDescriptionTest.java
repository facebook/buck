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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.ExplodingDownloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.net.URI;
import java.nio.file.Path;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RemoteFileDescriptionTest {

  @Rule public ExpectedException exception = ExpectedException.none();

  private Downloader downloader;
  private RemoteFileDescription description;
  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;

  @Before
  public void setUp() {
    downloader = new ExplodingDownloader();
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder().withToolchain(Downloader.DEFAULT_NAME, downloader).build();
    description = new RemoteFileDescription(toolchainProvider);
    filesystem = new FakeProjectFilesystem();
    graphBuilder = new TestActionGraphBuilder();
  }

  @Test
  public void badSha1HasUseableException() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:cake");

    RemoteFileDescriptionArg arg =
        RemoteFileDescriptionArg.builder()
            .setName(target.getShortName())
            .setSha1("")
            .setUrl(new URI("https://example.com/cheeeeeese-cake"))
            .build();

    exception.expect(HumanReadableException.class);
    exception.expectMessage(Matchers.containsString(target.getFullyQualifiedName()));

    description.createBuildRule(
        TestBuildRuleCreationContextFactory.create(graphBuilder, filesystem),
        target,
        RemoteFileBuilder.createBuilder(downloader, target)
            .from(arg)
            .createBuildRuleParams(graphBuilder),
        arg);
  }

  @Test
  public void remoteFileBinaryRuleIsCreatedForExecutableType() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//mmmm:kale");

    RemoteFileDescriptionArg arg =
        RemoteFileDescriptionArg.builder()
            .setName(target.getShortName())
            .setType(RemoteFile.Type.EXECUTABLE)
            .setSha1("cf23df2207d99a74fbe169e3eba035e633b65d94")
            .setOut("kale")
            .setUrl(new URI("https://example.com/tasty-kale"))
            .build();

    BuildRule buildRule =
        description.createBuildRule(
            TestBuildRuleCreationContextFactory.create(graphBuilder, filesystem),
            target,
            RemoteFileBuilder.createBuilder(downloader, target)
                .from(arg)
                .createBuildRuleParams(graphBuilder),
            arg);
    graphBuilder.addToIndex(buildRule);

    assertThat(buildRule, CoreMatchers.instanceOf(RemoteFileBinary.class));
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Tool executableCommand = ((RemoteFileBinary) buildRule).getExecutableCommand();
    assertThat(
        BuildableSupport.deriveInputs(executableCommand).collect(ImmutableList.toImmutableList()),
        Matchers.hasSize(1));
    SourcePath input =
        Iterables.getOnlyElement(
            BuildableSupport.deriveInputs(executableCommand)
                .collect(ImmutableList.toImmutableList()));
    Path absolutePath = pathResolver.getAbsolutePath(input);
    assertEquals("kale", absolutePath.getFileName().toString());
    assertEquals(
        ImmutableList.of(absolutePath.toString()),
        executableCommand.getCommandPrefix(pathResolver));
  }
}
