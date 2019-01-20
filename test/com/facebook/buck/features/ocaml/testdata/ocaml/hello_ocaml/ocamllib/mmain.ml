type m_type = {
    message: M1.m1_type;
}

let printM () =
    M2.printM2 ();
    M2.printHidden();
    (* M1.printHidden(); *)
    print_string "Hello M world!\n"
