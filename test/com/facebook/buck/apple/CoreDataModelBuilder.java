/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;

import java.nio.file.Path;

public class CoreDataModelBuilder extends AbstractNodeBuilder<CoreDataModelDescription.Arg> {

  protected CoreDataModelBuilder(BuildTarget target) {
    super(new CoreDataModelDescription(), target);
  }

  public static CoreDataModelBuilder createBuilder(BuildTarget target) {
    return new CoreDataModelBuilder(target);
  }

  public CoreDataModelBuilder setPath(Path path) {
    arg.path = path;
    return this;
  }

}
