#include <stdio.h>

#define LUA_LIB
#include <lua.h>
#include <lauxlib.h>

LUALIB_API int luaopen_foo (lua_State *L) {
  printf("hello world\n");
  return 0;
}
