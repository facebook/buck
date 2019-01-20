let () =
  let cmxs_path = Sys.argv.(1) in
  let cmxs_path = Dynlink.adapt_filename cmxs_path in
  (if Sys.file_exists cmxs_path then
    try Dynlink.loadfile cmxs_path
    with
    | (Dynlink.Error err) as e ->
      print_endline (Printf.sprintf "Error loading plugin: %s" (Dynlink.error_message err));
      raise e
    | e ->
      print_endline (Printf.sprintf "Unknown error while loading plugin");
      raise e
  else
    failwith "File does not exist!"
  );
  let p = Registry.get_plugin () in
  p ()
