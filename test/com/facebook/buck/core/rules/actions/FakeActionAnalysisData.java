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
package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.model.BuildTarget;

public class FakeActionAnalysisData implements ActionAnalysisData {

  private final BuildTarget buildTarget;

  private static final Key key = new Key() {};

  public FakeActionAnalysisData(BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
  }

  @Override
  public Key getKey() {
    return key;
  }

  @Override
  public BuildTarget getOwner() {
    return buildTarget;
  }
}
