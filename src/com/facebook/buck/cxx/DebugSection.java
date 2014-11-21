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

package com.facebook.buck.cxx;

import com.google.common.collect.ImmutableSet;

import java.nio.ByteBuffer;

/**
 * Encapsulates the various properties and data for a debug section of a native file format.
 */
public class DebugSection {

  /**
   * Properties describing the debug section data.
   */
  public final ImmutableSet<DebugSectionProperty> properties;

  /**
   * The buffer holding the contents of the debug section.
   */
  public final ByteBuffer body;

  public DebugSection(ImmutableSet<DebugSectionProperty> properties, ByteBuffer body) {
    this.properties = properties;
    this.body = body.duplicate();
  }

}
