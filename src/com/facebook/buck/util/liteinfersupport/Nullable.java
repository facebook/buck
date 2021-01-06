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

package com.facebook.buck.util.liteinfersupport;

/**
 * Some projects like FatJar is going to be embedded in many targets, it cannot have external
 * dependencies, but we'd like to have {@link javax.annotation.Nullable} and {@link
 * com.google.common.base.Preconditions#checkNotNull} anyway, so we define these here.
 */
public @interface Nullable {}
