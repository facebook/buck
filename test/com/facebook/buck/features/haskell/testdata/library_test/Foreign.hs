module Foreign where

foreign import ccall "func"
  c_func :: IO Int

foreignFunc :: IO Int
foreignFunc = c_func
