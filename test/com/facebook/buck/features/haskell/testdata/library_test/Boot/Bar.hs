module Boot.Bar where

import {-# SOURCE #-} Boot.Foo

bar :: Int -> Int
bar n = foo (n - 1) + foo (n - 2)
