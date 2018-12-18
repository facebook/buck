/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.versions;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternParser;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class QueryTargetTranslatorTest {

  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.get(new FakeProjectFilesystem());
  private static final BuildTargetPatternParser<BuildTargetPattern> PATTERN =
      BuildTargetPatternParser.fullyQualified();

  @Test
  public void translateTargets() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(new DefaultTypeCoercerFactory(), ImmutableMap.of(a, b));
    QueryTargetTranslator queryTranslator = new QueryTargetTranslator();
    assertThat(
        queryTranslator.translateTargets(
            CELL_PATH_RESOLVER, PATTERN, translator, Query.of("deps(//:a)")),
        Matchers.equalTo(Optional.of(Query.of("deps(//:b)"))));
  }

  @Test
  public void noTargets() {
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(new DefaultTypeCoercerFactory(), ImmutableMap.of());
    QueryTargetTranslator queryTranslator = new QueryTargetTranslator();
    assertThat(
        queryTranslator.translateTargets(
            CELL_PATH_RESOLVER, PATTERN, translator, Query.of("$declared_deps")),
        Matchers.equalTo(Optional.empty()));
  }
}
