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

package com.facebook.buck.ocaml;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * A set of OCaml related constants
 */
class OCamlCompilables {

  /**
   * Source files that can be preprocessed and compiled.
   */
  public static final ImmutableSet<String> SOURCE_EXTENSIONS =
      ImmutableSet.of("ml", "mli", "mly", "mll", "c");

  protected static final String OCAML_C = ".c";
  protected static final String OCAML_O = ".o";
  protected static final String OCAML_A = ".a";
  protected static final String OCAML_ML = ".ml";
  protected static final String OCAML_MLI = ".mli";
  protected static final String OCAML_CMX = ".cmx";
  protected static final String OCAML_CMO = ".cmo";
  protected static final String OCAML_CMI = ".cmi";
  protected static final String OCAML_MLY = ".mly";
  protected static final String OCAML_MLL = ".mll";
  protected static final String OCAML_C_REGEX = "\\.c$";
  protected static final String OCAML_ML_REGEX = "\\.ml$";
  protected static final String OCAML_MLI_REGEX = "\\.mli$";
  protected static final String OCAML_CMX_REGEX = "\\.cmx$";
  protected static final String OCAML_CMI_REGEX = "\\.cmi$";
  protected static final String OCAML_MLY_REGEX = "\\.mly$";
  protected static final String OCAML_MLL_REGEX = "\\.mll$";
  protected static final String OCAML_INCLUDE_FLAG = "-I";

  protected static final String OCAML_CMXA = ".cmxa";
  protected static final String OCAML_CMA = ".cma";
  protected static final String OCAML_CMXA_REGEX = "\\.cmxa$";

  protected static final String OCAML_CMT = ".cmt";
  protected static final String OCAML_CMTI = ".cmti";
  protected static final String OCAML_ANNOT = ".annot";

  protected static final String SYSTEM_SO = ".so";

  protected static final ImmutableList<String> DEFAULT_OCAML_FLAGS = ImmutableList.<String>builder()
      .add("-absname")
      .add("-g")
      .add("-noautolink")
      .build();

  private OCamlCompilables() {
  }

}
