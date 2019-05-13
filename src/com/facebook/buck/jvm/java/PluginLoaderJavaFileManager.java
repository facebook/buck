package com.facebook.buck.jvm.java;

import com.google.common.collect.ImmutableList;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public class PluginLoaderJavaFileManager extends ForwardingStandardJavaFileManager
    implements StandardJavaFileManager {

  private final PluginFactory pluginFactory;
  private final ImmutableList<JavacPluginJsr199Fields> javacPlugins;

  /**
   * Creates a new instance of ForwardingJavaFileManager.
   *
   * @param fileManager delegate to this file manager
   */
  protected PluginLoaderJavaFileManager(
      StandardJavaFileManager fileManager,
      PluginFactory pluginFactory,
      ImmutableList<JavacPluginJsr199Fields> javacPlugins) {
    super(fileManager);
    this.pluginFactory = pluginFactory;
    this.javacPlugins = javacPlugins;
  }

  @Override
  public ClassLoader getClassLoader(Location location) {
    // We only provide the shared classloader if there are plugins defined.
    if (StandardLocation.ANNOTATION_PROCESSOR_PATH.equals(location) &&
        javacPlugins.size() > 0) {
      return pluginFactory.getClassLoaderForProcessorGroups(javacPlugins);
    }
    return super.getClassLoader(location);
  }
}
