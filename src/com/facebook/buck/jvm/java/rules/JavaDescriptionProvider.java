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

package com.facebook.buck.jvm.java.rules;

import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.jvm.java.PrebuiltJarDescription;
import java.util.Collection;
import java.util.Collections;
import org.pf4j.Extension;

@Extension
public class JavaDescriptionProvider implements DescriptionProvider {
  @Override
  public Collection<Description<?>> getDescriptions(DescriptionCreationContext context) {
    return Collections.singleton(new PrebuiltJarDescription());
  }
}
