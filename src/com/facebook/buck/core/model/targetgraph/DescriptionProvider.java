/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import java.util.Collection;
import org.pf4j.ExtensionPoint;

/**
 * An {@link ExtensionPoint} which provides a way to register an arbitrary set of {@link
 * Description}s.
 *
 * <p>Clients that want to provide descriptions need to implement this interface and annotation that
 * class with {@link org.pf4j.Extension} annotation.
 *
 * <p>For example:
 *
 * <pre>
 *   &#64;Extension
 *   public class LanguageDescriptionProvider implements DescriptionProvider {
 *     &#64;Override
 *     public Collection&lt;&lt;Description&lt;?&gt;&gt; getDescriptions() {
 *       return Arrays.asList(
 *         new LanguageRuleDescription()
 *       );
 *     }
 *   }
 * </pre>
 *
 * Then the target with the client provider should be added to the dependencies of {@code
 * //src/com/facebook/buck/cli:main} target.
 */
public interface DescriptionProvider extends ExtensionPoint {
  Collection<Description<?>> getDescriptions(DescriptionCreationContext context);
}
