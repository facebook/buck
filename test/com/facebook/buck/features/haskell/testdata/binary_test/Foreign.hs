module Foreign where

foreign import ccall "func"
  c_func :: IO ()

main :: IO ()
main = c_func
