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

import com.facebook.buck.support.cli.args.PluginBasedSubCommands;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.ClassParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.IllegalAnnotationError;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.FieldSetter;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Setters;
import org.pf4j.PluginManager;

/**
 * {@link CmdLineParser} with nested options via the {@link AdditionalOptions} annotation.
 *
 * <p>Fields annotated with it should be of classes which have a default constructor, and will be
 * automatically instantiated.
 */
public class AdditionalOptionsCmdLineParser extends CmdLineParser {

  @SuppressWarnings("rawtypes")
  static final Comparator<OptionHandler> DEFAULT_COMPARATOR =
      Comparator.comparing(o -> o.option.toString());

  private final PluginManager pluginManager;

  /**
   * Creates a new command line owner that parses arguments/options and set them into the given
   * object.
   *
   * @param bean instance of a class annotated by {@link Option}, {@link Argument} and {@link
   *     AdditionalOptions}. This object will receive values. If this is null, the processing will
   *     be skipped, which is useful if you'd like to feed metadata from other sources.
   * @throws IllegalAnnotationError if the option bean class is using args4j annotations
   *     incorrectly.
   * @see CmdLineParser#CmdLineParser(Object)
   */
  public AdditionalOptionsCmdLineParser(PluginManager pluginManager, Object bean) {
    super(null);
    this.pluginManager = pluginManager;

    // This is copied in simplified form from CmdLineParser constructor and put here to save the
    // reference to the plugin manager before doing the parsing of options.
    new ClassParser().parse(bean, this);
    getOptions().sort(DEFAULT_COMPARATOR);

    Set<Class<?>> visited = new HashSet<>();
    parseAnnotations(new ClassParser(), bean, visited);
  }

  public PluginManager getPluginManager() {
    return pluginManager;
  }

  private void parseAnnotations(ClassParser classParser, Object bean, Set<Class<?>> visited) {
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
      parseAdditionalOptions(classParser, bean, visited, f);
      parsePluginBasedOption(classParser, bean, visited, f);
    }
  }

  @SuppressWarnings("unchecked")
  private void parseAdditionalOptions(
      ClassParser classParser, Object bean, Set<Class<?>> visited, Field f) {
    if (f.isAnnotationPresent(AdditionalOptions.class)) {
      try {
        // TODO(vlada): nicer to do this lazily in parseArgument()
        Object fieldValue = f.getType().newInstance();
        Setters.create(f, bean).addValue(fieldValue);
        parseAnnotations(classParser, fieldValue, visited);
      } catch (CmdLineException | IllegalAccessException | InstantiationException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void parsePluginBasedOption(
      ClassParser classParser, Object bean, Set<Class<?>> visited, Field f) {
    if (f.isAnnotationPresent(PluginBasedSubCommands.class)) {
      PluginBasedSubCommands optionAnnotation = f.getAnnotation(PluginBasedSubCommands.class);
      ImmutableList<Object> commands =
          ImmutableList.copyOf(pluginManager.getExtensions(optionAnnotation.commandClass()));
      new FieldSetter(bean, f).addValue(commands);
      commands.forEach(cmd -> parseAnnotations(classParser, cmd, visited));
    }
  }
}
