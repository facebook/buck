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

package com.facebook.buck.rules.query;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;

public class QueryUtilsTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:rule");
  private static final CellPathResolver CELL_NAMES =
      TestCellPathResolver.get(new FakeProjectFilesystem());

  @Test
  public void extractParseTimeTargets() {
    assertThat(
        QueryUtils.extractParseTimeTargets(TARGET, CELL_NAMES, Query.of("deps(//some:rule)"))
            .collect(Collectors.toList()),
        Matchers.contains(BuildTargetFactory.newInstance("//some:rule")));
  }
}
