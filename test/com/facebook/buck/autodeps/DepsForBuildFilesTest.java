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

package com.facebook.buck.autodeps;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DepsForBuildFilesTest {

  @Test
  public void testIterateDeps() {
    DepsForBuildFiles depsForBuildFiles = new DepsForBuildFiles();

    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/example:foo");
    BuildTarget barTarget = BuildTargetFactory.newInstance("//java/com/example:bar");
    BuildTarget bazTarget = BuildTargetFactory.newInstance("//java/com/example/baz:baz");

    BuildTarget aTarget = BuildTargetFactory.newInstance("//java/com/example:a");
    BuildTarget bTarget = BuildTargetFactory.newInstance("//java/com/example:b");
    BuildTarget cTarget = BuildTargetFactory.newInstance("//java/com/example:c");
    BuildTarget dTarget = BuildTargetFactory.newInstance("//java/com/example:d");
    BuildTarget eTarget = BuildTargetFactory.newInstance("//java/com/example:e");
    BuildTarget fTarget = BuildTargetFactory.newInstance("//java/com/example:f");
    BuildTarget gTarget = BuildTargetFactory.newInstance("//java/com/example:g");
    BuildTarget hTarget = BuildTargetFactory.newInstance("//java/com/example:h");
    BuildTarget iTarget = BuildTargetFactory.newInstance("//java/com/example:i");

    depsForBuildFiles.addDep(fooTarget, barTarget);
    depsForBuildFiles.addDep(fooTarget, bazTarget);

    depsForBuildFiles.addDep(barTarget, bazTarget);

    // Add in seemingly random order so we can verify that we don't get things back in alphabetical
    // order because we added things in alphabetical order.
    depsForBuildFiles.addDep(bazTarget, gTarget);
    depsForBuildFiles.addDep(bazTarget, hTarget);
    depsForBuildFiles.addDep(bazTarget, aTarget);
    depsForBuildFiles.addDep(bazTarget, eTarget);
    depsForBuildFiles.addDep(bazTarget, bTarget);
    depsForBuildFiles.addDep(bazTarget, iTarget);
    depsForBuildFiles.addDep(bazTarget, dTarget);
    depsForBuildFiles.addDep(bazTarget, fTarget);
    depsForBuildFiles.addDep(bazTarget, cTarget);

    Map<BuildTarget, List<BuildTarget>> observed = new HashMap<>();
    for (DepsForBuildFiles.BuildFileWithDeps buildFileWithDeps : depsForBuildFiles) {
      Path directory = buildFileWithDeps.getBasePath();
      for (DepsForBuildFiles.DepsForRule depsForRule : buildFileWithDeps) {
        String fullyQualifiedName = String.format("//%s:%s", directory, depsForRule.getShortName());
        BuildTarget buildTarget = BuildTargetFactory.newInstance(fullyQualifiedName);

        List<BuildTarget> deps = ImmutableList.copyOf(depsForRule);
        observed.put(buildTarget, deps);
      }
    }

    assertEquals(
        ImmutableMap.builder()
            .put(
                fooTarget,
                ImmutableList.of(
                    barTarget,
                    bazTarget
                )
            )
            .put(
                barTarget,
                ImmutableList.of(
                    bazTarget
                )
            )
            .put(
                bazTarget,
                ImmutableList.of(
                    aTarget,
                    bTarget,
                    cTarget,
                    dTarget,
                    eTarget,
                    fTarget,
                    gTarget,
                    hTarget,
                    iTarget
                )
            )
            .build(),
        observed
    );
  }
}
