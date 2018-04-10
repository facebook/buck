let registered_plugin: (unit -> unit) option ref = ref None
let get_plugin () : (unit -> unit) =
  match !registered_plugin with
  | Some p -> p
  | None -> failwith "No plugin has been registered!"
