/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.model;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.impl.JsonTargetConfigurationSerializer;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;

public class TargetConfigurationSerializerForTests implements TargetConfigurationSerializer {

  private final CellPathResolver cellPathResolver;

  public TargetConfigurationSerializerForTests(CellPathResolver cellPathResolver) {
    this.cellPathResolver = cellPathResolver;
  }

  @Override
  public String serialize(TargetConfiguration targetConfiguration) {
    UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory =
        new ParsingUnconfiguredBuildTargetFactory();
    return new JsonTargetConfigurationSerializer(
            targetName -> unconfiguredBuildTargetFactory.create(cellPathResolver, targetName))
        .serialize(targetConfiguration);
  }

  @Override
  public TargetConfiguration deserialize(String rawValue) {
    return EmptyTargetConfiguration.INSTANCE;
  }

  public static TargetConfigurationSerializer create(CellPathResolver cellPathResolver) {
    return new TargetConfigurationSerializerForTests(cellPathResolver);
  }
}
