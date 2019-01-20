package lib

import "testing"

func TestTestScores(t *testing.T) {
	if TestScores() < 60 {
		t.Errorf("What a Loser!")
	}
}
