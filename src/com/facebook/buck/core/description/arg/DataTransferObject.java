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

package com.facebook.buck.core.description.arg;

/**
 * Immutable object is marked with this marker, and this object has a builder companion, e. g. for
 * {@code MyData} there's {@code MyData.Builder}, that builder is created with static {@code
 * builder()} method, and the builder has {@code build()} method to build {@code MyData}.
 */
public interface DataTransferObject {}
