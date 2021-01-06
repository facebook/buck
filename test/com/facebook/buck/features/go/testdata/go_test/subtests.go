package lib

import (
	"fmt"
	"testing"
)

func TestAddition(t *testing.T) {
	tt := []struct {
		a, b int
		want int
	}{
		{2, 2, 4},
		{99, -1, 98},
		{0, 0, 0},
	}
	for _, tc := range tt {
		t.Run(fmt.Sprintf("%d+%d", tc.a, tc.b), func(t *testing.T) {
			got := tc.a + tc.b
			if got != tc.want {
				t.Errorf("got %d, want %d", got, tc.want)
			}
		})
	}
}
