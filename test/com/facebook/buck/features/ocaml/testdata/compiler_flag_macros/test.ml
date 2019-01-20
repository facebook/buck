open Asttypes
open Parsetree
open Ast_mapper

let test_mapper argv =
  { default_mapper with
    expr = fun mapper expr ->
      match expr with
      | { pexp_desc = Pexp_extension ({ txt = "test" }, PStr [])} ->
        Ast_helper.Exp.constant (Ast_helper.Const.int 42)
      | other -> default_mapper.expr mapper other; }

let () =
  register "ppx_test" test_mapper
