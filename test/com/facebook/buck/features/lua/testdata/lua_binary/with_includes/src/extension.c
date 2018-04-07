#include <stdio.h>
#include "extension.h"

#include <lua.h>

int luaopen_extension (lua_State *L) {
  printf("%s\n", hello());
  return 0;
}
