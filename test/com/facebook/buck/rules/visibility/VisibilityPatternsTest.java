/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.visibility;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class VisibilityPatternsTest {
  @Test(expected = HumanReadableException.class)
  public void bogusVisibilityGivesFriendlyError() {
    VisibilityPatterns.createFromStringList(
        createCellRoots(new FakeProjectFilesystem()),
        "visibility",
        ImmutableList.of(":marmosets"),
        BuildTargetFactory.newInstance("//example/path:three"));
  }
}
