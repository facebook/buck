#include <Contacts/lib.h>

int main(int argc, char *argv[]) {
  int contacts = get_contacts();
  int location = get_location();
  return contacts + location;
}
