/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import java.util.EnumSet;

public class BuildableProperties {
  public static final BuildableProperties NONE = new BuildableProperties();

  public static enum Kind {
    /**
     * Whether this rule exists only in an Android project.
     */
    ANDROID,

    LIBRARY,

    /**
     * Whether or not this rule is considered a packaging rule.  Packaging rules
     *   (like java_binary) are rules that package up all of their transitive dependencies in their
     *   final output.
     */
    PACKAGING,

    TEST,
    ;
  }

  private final EnumSet<Kind> properties;

  public BuildableProperties(Kind primaryKind, Kind... kinds) {
    this.properties = EnumSet.of(primaryKind, kinds);
  }

  private BuildableProperties() {
    properties = EnumSet.noneOf(Kind.class);
  }

  public boolean is(Kind kind) {
    return properties.contains(kind);
  }
}
