/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.google.common.base.Function;

/**
 * Responsible for converting a {@link TargetGraph} to a graph of some other type, typically of
 * {@link BuildRule}s, but there's not requirement for this to be the case.
 */
public interface TargetGraphTransformer<T extends DirectedAcyclicGraph<?>>
    extends Function<TargetGraph, T> {

    @Override
    T apply(TargetGraph input);
}
