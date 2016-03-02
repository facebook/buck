// +build !amd64

package main

func Sum(xs []int64) int64 {
  var n int64
  for _, v := range xs {
    n += v
  }
  return n
}
