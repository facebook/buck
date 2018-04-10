let show_it_works () = print_endline "it works!"
let () = Registry.registered_plugin := Some show_it_works;
