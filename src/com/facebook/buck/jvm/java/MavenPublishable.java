/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSortedSet;

/**
 * A {@link BuildRule} that can have its output({@link #getPathToOutput}) published to a
 * maven repository under the maven coordinates provided by {@link #getMavenCoords}
 */
public interface MavenPublishable extends HasMavenCoordinates {

  /**
   * When published, these will be listed in pom.xml as dependencies
   */
  ImmutableSortedSet<HasMavenCoordinates> getMavenDeps();
}
