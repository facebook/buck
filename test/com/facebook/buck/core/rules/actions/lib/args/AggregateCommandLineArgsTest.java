/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.core.rules.actions.lib.args;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.providers.impl.UserDefinedProvider;
import com.facebook.buck.core.rules.providers.impl.UserDefinedProviderInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableRunInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AggregateCommandLineArgsTest {

  private static RunInfo createRunInfo(
      ImmutableSortedMap<String, String> env, ImmutableList<Object> args) {
    return new ImmutableRunInfo(
        env, new ListCommandLineArgs(args, CommandLineArgs.DEFAULT_FORMAT_STRING));
  }

  @Test
  public void returnsProperStreamAndArgCount() throws EvalException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact path1 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));

    BuildTarget target = BuildTargetFactory.newInstance("//:some_rule");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
    Artifact artifact3 = registry.declareArtifact(Paths.get("out.txt"), Location.BUILTIN);
    OutputArtifact artifact3Output =
        (OutputArtifact) artifact3.asSkylarkOutputArtifact(Location.BUILTIN);
    Path artifact3Path = BuildPaths.getGenDir(filesystem, target).resolve("out.txt");

    CommandLineArgs args =
        new AggregateCommandLineArgs(
            ImmutableList.of(
                createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1)),
                createRunInfo(ImmutableSortedMap.of("BAZ", "baz_val"), ImmutableList.of(path2, 1)),
                CommandLineArgsFactory.from(ImmutableList.of("foo", "bar", artifact3Output))));

    new WriteAction(
        registry,
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(artifact3Output),
        "contents",
        false);

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(args);

    assertEquals(
        ImmutableList.of(
            filesystem.resolve("some_bin").toAbsolutePath().toString(),
            "other_file",
            "1",
            "foo",
            "bar",
            artifact3Path.toString()),
        cli.getCommandLineArgs());
    assertEquals(6, args.getEstimatedArgsCount());
    assertEquals(
        ImmutableSortedMap.of("FOO", "foo_val", "BAZ", "baz_val"), cli.getEnvironmentVariables());

    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<OutputArtifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);

    assertEquals(ImmutableSortedSet.of(path1, path2), inputs.build());
    assertEquals(ImmutableSortedSet.of(artifact3Output), outputs.build());
  }

  @Test
  public void formatsStrings() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact path1 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in1.txt")));
    Artifact path2 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in2.txt")));
    Artifact path3 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in3.txt")));
    Artifact path4 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in4.txt")));

    CommandLineArgs args =
        new AggregateCommandLineArgs(
            ImmutableList.of(
                CommandLineArgsFactory.from(ImmutableList.of("foo", "bar")),
                CommandLineArgsFactory.from(ImmutableList.of(path1, path2), "--in=%s"),
                CommandLineArgsFactory.from(ImmutableList.of(path3, path4), "--other-in=%s")));

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(args);

    assertEquals(
        ImmutableList.of(
            "foo",
            "bar",
            "--in=in1.txt",
            "--in=in2.txt",
            "--other-in=in3.txt",
            "--other-in=in4.txt"),
        cli.getCommandLineArgs());
    assertEquals(6, args.getEstimatedArgsCount());

    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<Artifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, o -> outputs.add(o.getArtifact()));

    assertEquals(ImmutableSortedSet.of(path1, path2, path3, path4), inputs.build());
  }

  @Test
  public void ruleKeyChangesOnChanges() throws IOException, EvalException {
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    HashMap<Path, HashCode> hashes = new HashMap<>();
    hashes.put(filesystem.resolve("some_bin"), HashCode.fromString("aaaa"));
    hashes.put(filesystem.resolve("other_file"), HashCode.fromString("bbbb"));

    FakeFileHashCache hashCache = new FakeFileHashCache(hashes);

    Function<Object, HashCode> compute =
        obj ->
            new TestDefaultRuleKeyFactory(hashCache, ruleFinder)
                .newBuilderForTesting(new FakeBuildRule(target))
                .setReflectively("field", obj)
                .build();

    BuildArtifactFactoryForTests buildArtifactFactory =
        new BuildArtifactFactoryForTests(target, filesystem);
    Artifact path3 =
        buildArtifactFactory.createDeclaredArtifact(Paths.get("_foo_output_"), Location.BUILTIN);
    Artifact path4 =
        buildArtifactFactory.createDeclaredArtifact(Paths.get("_foo_output_"), Location.BUILTIN);
    Artifact path5 =
        buildArtifactFactory.createDeclaredArtifact(Paths.get("_bar_output_"), Location.BUILTIN);

    Artifact path1 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 = SourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));

    ListCommandLineArgs listArgs1 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs2 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs3 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs4 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path3.asOutputArtifact()),
            CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs5 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path4.asOutputArtifact()),
            CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs6 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path5.asOutputArtifact()),
            CommandLineArgs.DEFAULT_FORMAT_STRING);

    RunInfo envArgs1 =
        createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    RunInfo envArgs2 =
        createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    RunInfo envArgs3 =
        createRunInfo(ImmutableSortedMap.of("BAR", "bar_val"), ImmutableList.of(path1));
    RunInfo envArgs4 =
        createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));

    assertEquals(compute.apply(envArgs1), compute.apply(envArgs2));
    assertNotEquals(compute.apply(envArgs1), compute.apply(envArgs3));

    assertEquals(compute.apply(listArgs1), compute.apply(listArgs2));
    assertEquals(compute.apply(listArgs1), compute.apply(listArgs3));

    assertEquals(
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1))),
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs2, listArgs2))));

    assertNotEquals(
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1))),
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs3, listArgs1))));

    assertEquals(
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1))),
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs3))));

    HashCode listKey1 = compute.apply(listArgs1);
    HashCode envKey1 = compute.apply(envArgs1);
    HashCode aggKey1 =
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1)));

    hashCache.set(filesystem.resolve("some_bin"), HashCode.fromString("cccc"));

    assertNotEquals(listKey1, compute.apply(listArgs1));
    assertNotEquals(envKey1, compute.apply(envArgs1));
    assertNotEquals(
        aggKey1,
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1))));

    assertNotEquals(compute.apply(listArgs1), compute.apply(listArgs4));
    assertEquals(compute.apply(listArgs4), compute.apply(listArgs5));

    assertNotEquals(
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1))),
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs4))));

    assertEquals(
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs4))),
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs5))));

    assertNotEquals(
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs4))),
        compute.apply(new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs6))));
  }

  @Test
  public void isImmutable() throws LabelSyntaxException, EvalException, InterruptedException {
    CommandLineArgs args1 = CommandLineArgsFactory.from(ImmutableList.of(1, 2, 3));
    CommandLineArgs args2 = CommandLineArgsFactory.from(ImmutableList.of(1, 2, 3));
    CommandLineArgs args = CommandLineArgsFactory.from(ImmutableList.of(args1, args2));

    assertThat(args, Matchers.instanceOf(AggregateCommandLineArgs.class));
    assertTrue(args.isImmutable());

    UserDefinedProvider provider = new UserDefinedProvider(Location.BUILTIN, new String[] {"foo"});
    provider.export(Label.parseAbsolute("//:foo.bzl", ImmutableMap.of()), "provider");
    try (TestMutableEnv env = new TestMutableEnv()) {
      UserDefinedProviderInfo providerInfo =
          (UserDefinedProviderInfo)
              provider.callWithArgArray(new Object[] {args}, null, env.getEnv(), Location.BUILTIN);
      assertEquals(args, providerInfo.getValue("foo"));
      assertTrue(providerInfo.isImmutable());
    }
  }
}
