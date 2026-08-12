package main

import (
	"errors"
	"testing"
)

// P1-6: ws-gateway must fail closed in production when the JWT public key
// cannot be loaded — anonymous WebSocket auth is never allowed in production.

func TestShouldFailClosedProduction(t *testing.T) {
	t.Setenv("APP_ENV", "production")
	if !shouldFailClosed() {
		t.Fatal("expected shouldFailClosed() == true when APP_ENV=production")
	}
}

func TestShouldFailClosedDev(t *testing.T) {
	for _, env := range []string{"", "dev", "staging", "test"} {
		t.Setenv("APP_ENV", env)
		if shouldFailClosed() {
			t.Fatalf("expected shouldFailClosed() == false for APP_ENV=%q", env)
		}
	}
}

func TestEvaluateKeyLoadProductionFailsClosed(t *testing.T) {
	loadErr := errors.New("no key configured")
	if !evaluateKeyLoad(loadErr, "production") {
		t.Fatal("production must fail closed when key load fails")
	}
}

func TestEvaluateKeyLoadDevContinues(t *testing.T) {
	loadErr := errors.New("no key configured")
	for _, env := range []string{"", "dev", "staging", "test"} {
		if evaluateKeyLoad(loadErr, env) {
			t.Fatalf("dev env %q must NOT fail closed", env)
		}
	}
}

func TestFailClosedMessageContainsProductionMarker(t *testing.T) {
	loadErr := errors.New("no key configured")
	msg := failClosedMessage(loadErr)
	for _, want := range []string{"FATAL", "production", "no key configured", "refusing to start"} {
		if !contains(msg, want) {
			t.Fatalf("failClosedMessage missing %q: %q", want, msg)
		}
	}
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (haystack == needle ||
		(func() bool {
			for i := 0; i+len(needle) <= len(haystack); i++ {
				if haystack[i:i+len(needle)] == needle {
					return true
				}
			}
			return false
		}()))
}
