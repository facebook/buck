#define LUA_LIB
#include <lua.h>
#include <lauxlib.h>

#include "dep.h"

LUALIB_API int luaopen_bar(lua_State *L) {
  dep();
  return 0;
}
