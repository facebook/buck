package com.facebook.buck.util;

import com.facebook.buck.io.filesystem.PathOrGlobMatcher;

public class PathMatchers {

  public static final PathOrGlobMatcher KOTLIN_PATH_MATCHER = new PathOrGlobMatcher("**.kt");

  public static final PathOrGlobMatcher JAVA_PATH_MATCHER = new PathOrGlobMatcher("**.java");

}
