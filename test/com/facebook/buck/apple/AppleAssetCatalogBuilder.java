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
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.Set;

public class AppleAssetCatalogBuilder
    extends AbstractNodeBuilder<AppleAssetCatalogDescription.Arg> {

  protected AppleAssetCatalogBuilder(BuildTarget target) {
    super(new AppleAssetCatalogDescription(), target);
  }

  public static AppleAssetCatalogBuilder createBuilder(BuildTarget target) {
    return new AppleAssetCatalogBuilder(target);
  }

  public AppleAssetCatalogBuilder setDirs(Set<Path> dirs) {
    arg.dirs = dirs;
    return this;
  }

  public AppleAssetCatalogBuilder setCopyToBundles(Optional<Boolean> copyToBundles) {
    arg.copyToBundles = copyToBundles;
    return this;
  }

}
