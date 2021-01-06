/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.js;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;

/**
 * Empty interface to mark {@link DescriptionWithTargetGraph} subclasses that return {@link
 * JsBundleOutputs} instances from their {@link DescriptionWithTargetGraph#createBuildRule} method.
 */
public interface JsBundleOutputsDescription<T extends BuildRuleArg>
    extends DescriptionWithTargetGraph<T> {}
