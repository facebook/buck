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

package com.facebook.buck.core.artifact.converter;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SourceArtifactConverterTest {

  @Test
  public void convertsListOfSources() {
    BuildTarget target = BuildTargetFactory.newInstance("//test:foo");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildArtifactFactoryForTests artifactFactoryForTests =
        new BuildArtifactFactoryForTests(target, filesystem);

    Artifact artifact =
        artifactFactoryForTests.createBuildArtifact(Paths.get("out"), Location.BUILTIN);

    ProviderInfoCollection dep =
        TestProviderInfoCollectionImpl.builder()
            .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableSet.of(artifact)));

    ImmutableMap<BuildTarget, ProviderInfoCollection> deps = ImmutableMap.of(target, dep);

    PathSourcePath pathSourcePath = PathSourcePath.of(filesystem, Paths.get("source"));
    DefaultBuildTargetSourcePath buildTargetSourcePath =
        DefaultBuildTargetSourcePath.of(
            BuildTargetWithOutputs.of(target, OutputLabel.defaultLabel()));

    ImmutableSet<SourcePath> sources = ImmutableSet.of(pathSourcePath, buildTargetSourcePath);

    ImmutableSortedSet<Artifact> artifacts =
        SourceArtifactConverter.getArtifactsFromSrcs(sources, deps);

    assertThat(
        artifacts,
        Matchers.containsInAnyOrder(
            SourceArtifactImpl.of(pathSourcePath),
            Iterables.getOnlyElement(dep.getDefaultInfo().defaultOutputs())));
  }
}
