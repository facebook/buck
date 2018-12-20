package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * Represents a standard Java Compiler Plugin, that is, not an annotation processor
 */
public class StandardJavacPlugin extends JavacPlugin {

  public StandardJavacPlugin(BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavacPluginProperties properties) {
    super(buildTarget, projectFilesystem, params, properties);
  }
}
