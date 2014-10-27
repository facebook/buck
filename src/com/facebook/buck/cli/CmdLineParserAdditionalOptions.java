/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.google.common.base.Throwables;
import com.google.common.collect.Sets;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.ClassParser;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.IllegalAnnotationError;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.Setters;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * {@link CmdLineParser} with nested options via the {@link AdditionalOptions} annotation.
 * <p>
 * Fields annotated with it should be of classes which have a default constructor, and will be
 * automatically instantiated.
 */
public class CmdLineParserAdditionalOptions extends CmdLineParser {

  /**
   * Creates a new command line owner that parses arguments/options and set them into
   * the given object.
   *
   * @param bean
   *      instance of a class annotated by {@link Option}, {@link Argument}
   *      and {@link AdditionalOptions}.
   *      This object will receive values. If this is null, the processing will
   *      be skipped, which is useful if you'd like to feed metadata from other sources.
   *
   * @throws IllegalAnnotationError
   *      if the option bean class is using args4j annotations incorrectly.
   * @see CmdLineParser#CmdLineParser(Object)
   */
  public CmdLineParserAdditionalOptions(Object bean) {
    super(bean);
    Set<Class<?>> visited = Sets.newHashSet();
    parseAdditionalOptions(new ClassParser(), bean, visited);
  }

  @SuppressWarnings("unchecked")
  private void parseAdditionalOptions(ClassParser classParser, Object bean, Set<Class<?>> visited) {
    // The top-level bean was already parsed by the superclass constructor,
    // so an empty visited set means we're parsing the top-level bean.
    if (!visited.isEmpty()) {
      classParser.parse(bean, this); // 'Parse' the class of the bean looking for annotations.
    }
    Class<?> beanClass = bean.getClass();
    if (visited.contains(beanClass)) {
      throw new IllegalAnnotationError(beanClass.getCanonicalName() + " used more than once.");
    } else {
      visited.add(beanClass);
    }

    for (Field f : beanClass.getDeclaredFields()) {
      if (f.isAnnotationPresent(AdditionalOptions.class)) {
        try {
          // TODO(user): nicer to do this lazily in parseArgument()
          Object fieldValue = f.getType().newInstance();
          Setters.create(f, bean).addValue(fieldValue);
          parseAdditionalOptions(classParser, fieldValue, visited);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }
    }
  }

}
