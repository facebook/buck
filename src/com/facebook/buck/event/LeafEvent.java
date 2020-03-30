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

package com.facebook.buck.event;

/**
 * LeafEvents represent events that have no logical children and are useful for tracking the time
 * buck is spending logically grouped by categories, such as Parse, Javac, or Dx. An example of
 * something that wouldn't be a good candidate for a LeafEvent would be a RuleEvent since we're not
 * spending an appreciable amount of time in the rules but in the steps instead.
 */
public interface LeafEvent extends BuckEvent {
  /** @return The category time spent in this event should be accounted under. */
  String getCategory();
}
