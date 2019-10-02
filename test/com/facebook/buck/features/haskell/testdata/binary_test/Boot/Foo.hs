module Boot.Foo where

import Boot.Bar

foo :: Int -> Int
foo 0 = 0
foo 1 = 1
foo n = bar n

main :: IO ()
main = print $ foo 10
