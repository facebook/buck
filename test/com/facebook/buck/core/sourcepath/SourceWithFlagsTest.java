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

package com.facebook.buck.core.sourcepath;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SourceWithFlagsTest {
  @Test
  public void translateSourceWithFlags() {
    CellPathResolver cellPathResolver = TestCellPathResolver.get(new FakeProjectFilesystem());
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    TargetNodeTranslator translator =
        new TargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableList.of(), new TestCellBuilder().build()) {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.of(b);
          }

          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.empty();
          }
        };
    assertThat(
        SourceWithFlags.of(
                DefaultBuildTargetSourcePath.of(a),
                ImmutableList.of(StringWithMacrosUtils.format("-flag=%s", LocationMacro.of(a))))
            .translateTargets(cellPathResolver.getCellNameResolver(), BaseName.ROOT, translator),
        Matchers.equalTo(
            Optional.of(
                SourceWithFlags.of(
                    DefaultBuildTargetSourcePath.of(b),
                    ImmutableList.of(
                        StringWithMacrosUtils.format("-flag=%s", LocationMacro.of(b)))))));
  }
}
