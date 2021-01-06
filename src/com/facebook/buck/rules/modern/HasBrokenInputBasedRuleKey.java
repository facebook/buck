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

package com.facebook.buck.rules.modern;

/**
 * Used to indicate that this Buildable's input-based key doesn't properly reflect all the inputs to
 * this rule. This shouldn't be necessary and if used is likely an indication of a bug in either the
 * ModernBuildRule framework or this Buildable's implementation.
 */
public interface HasBrokenInputBasedRuleKey {}
