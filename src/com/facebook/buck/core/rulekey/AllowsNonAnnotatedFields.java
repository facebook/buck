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
package com.facebook.buck.core.rulekey;

/**
 * Marks classes where we shouldn't report on fields that aren't annotated
 * with @AddToRuleKey/@ExcludeFromRuleKey.
 *
 * <p>This is only intended for some very limited uses. For example, we apply this to BuildRule
 * objects because we know that they are currently being widely used to propagate Provider-like
 * information rather than holding input state to the action.
 */
public interface AllowsNonAnnotatedFields {}
