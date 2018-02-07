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

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;

/**
 * A Buildable is the core of a ModernBuildRule. A ModernBuildRule's deps, rulekey, inputs, outputs,
 * etc are all derived (via reflection) from the fields of a Buildable.
 *
 * <p>Buildables must be a top-level class or static inner class. Buildable fields must be final.
 * Fields are limited to primitive types, Strings, Input/OutputPath, Input/OutputData and
 * ImmutableList, Optional, ImmutableSortedSet, ImmutableSortedMap, and Pair composed of those.
 */
public interface Buildable extends AddsToRuleKey {
  // TODO(cjhopman): The filesystem object here should verify that reads/writes only
  // go to the declared InputPaths and OutputPaths.
  // TODO(cjhopman): The resolvers/retrievers should verify that requests are only for
  // inputs/outputs that are detected by the classinfo.
  // TODO(cjhopman): The ExecutionContext passed into the Steps has access to a bunch of stuff that
  // isn't reflected in the rulekeys. We should either pass in a filtered context, or make a new
  // safety-first ModernStep and have this return a list of those. That would be painful for
  // migration so we'd probably want something like ModernStep.fromUnsafeClassicStep(...).
  ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory);
}
