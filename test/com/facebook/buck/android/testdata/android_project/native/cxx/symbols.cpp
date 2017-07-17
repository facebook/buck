extern "C" {
  int __attribute__ ((noinline, visibility ("hidden"))) supply_value() {
    return 42;
  }

  int get_value() {
    return supply_value();
  }
}
