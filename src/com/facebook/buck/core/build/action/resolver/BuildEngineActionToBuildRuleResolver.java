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
package com.facebook.buck.core.build.action.resolver;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.rules.BuildRule;

/**
 * Resolves a {@link BuildEngineAction} into the corresponding {@link BuildRule}.
 *
 * <p>This only exists due to legacy compatibility reasons so that we can incrementally migrate the
 * build engine to use {@link BuildEngineAction}
 */
public class BuildEngineActionToBuildRuleResolver {

  public BuildRule resolve(BuildEngineAction action) {
    // TODO(bobyf): add support for Actions
    return BuildRule.class.cast(action);
  }
}
