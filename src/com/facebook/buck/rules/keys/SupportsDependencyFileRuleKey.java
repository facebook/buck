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

package com.facebook.buck.rules.keys;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Used to tag a rule that supports dependency-file input-based rule keys.
 */
public interface SupportsDependencyFileRuleKey {

  boolean useDependencyFileRuleKeys();

  ImmutableList<Path> getInputsAfterBuildingLocally() throws IOException;

  Optional<ImmutableMultimap<String, String>> getSymlinkTreeInputMap() throws IOException;

}
