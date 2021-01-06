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

/**
 * Enables generation of ABI jars using only the Java source code of a single target, without
 * requiring access to the source or ABI of dependencies.
 *
 * <p>If the ABI of a dependency target is available, it will be used. However, when one or more
 * dependency ABIs are missing, certain language constructs become ambiguous, and we have to make
 * assumptions. Most of those assumptions can be worked around with small changes to coding style.
 *
 * <p>See {@link com.facebook.buck.jvm.java.abi.source.InterfaceValidator} for more information on
 * the restrictions.
 */
package com.facebook.buck.jvm.java.abi.source;
