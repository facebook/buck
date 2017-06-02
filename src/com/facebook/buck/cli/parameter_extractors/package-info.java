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

/**
 * A package for interfaces that extract parameters from {@code
 * com.facebook.buck.cli.AbstractCommand} descendants. Those descendants can then implement one or
 * more of these interfaces, and pass the interface to out-of-package implementations of commands,
 * instead of having to pass a large number of parameters to those implementations. When an
 * implementation needs, say, a new command line switch, one just changes the interface and (maybe)
 * adds a new method to the {@code AbstractCommand} descendant; there is no need to modify the
 * implementation's parameter list or call site; when an implementation is behind a 'helper', the
 * helper just passes the extractor interface, with no need to add fields to the helper and change
 * multiple parameters lists and call sites.
 */
package com.facebook.buck.cli.parameter_extractors;
