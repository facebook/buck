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

package com.facebook.buck.core.artifact;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Paths;
import org.junit.Test;

public class ArtifactTest {

  @Test
  public void sortStable() {

    BuildTarget target = BuildTargetFactory.newInstance("//test:foo");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(target, filesystem);

    Artifact artifact1 =
        artifactFactory.createDeclaredArtifact(Paths.get("path1"), Location.BUILTIN);
    Artifact artifact2 =
        artifactFactory.createDeclaredArtifact(Paths.get("path2"), Location.BUILTIN);
    Artifact artifact3 = artifactFactory.createBuildArtifact(Paths.get("path3"), Location.BUILTIN);
    Artifact artifact4 = artifactFactory.createBuildArtifact(Paths.get("path4"), Location.BUILTIN);
    Artifact artifact5 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("path5")));

    ImmutableSortedSet.Builder<Artifact> artifacts1 = ImmutableSortedSet.naturalOrder();

    artifacts1.add(artifact1);
    artifacts1.add(artifact2);
    artifacts1.add(artifact3);
    artifacts1.add(artifact4);
    artifacts1.add(artifact5);

    ImmutableSortedSet.Builder<Artifact> artifacts2 = ImmutableSortedSet.naturalOrder();

    artifacts2.add(artifact4);
    artifacts2.add(artifact1);
    artifacts2.add(artifact3);
    artifacts2.add(artifact5);
    artifacts2.add(artifact2);

    ImmutableSortedSet<Artifact> artifactsResult1 = artifacts1.build();
    ImmutableSortedSet<Artifact> artifactsResult2 = artifacts2.build();
    assertEquals(artifactsResult1, artifactsResult2);
    assertEquals(5, artifactsResult1.size());
  }
}
