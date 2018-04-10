open Asttypes
open Parsetree
open Ast_mapper

let test_mapper argv =
  { default_mapper with
    expr = fun mapper expr ->
      match expr with
      | { pexp_desc = Pexp_extension ({ txt = "transform_me" }, PStr [{ pstr_desc = Pstr_eval (expr, _); _;}])} ->
        expr
      | other -> default_mapper.expr mapper other; }

let () =
  register "my_ppx" test_mapper
