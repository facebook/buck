void static_func_F();
void prebuilt_func_F();
void F() {
  static_func_F();
  prebuilt_func_F();
}
