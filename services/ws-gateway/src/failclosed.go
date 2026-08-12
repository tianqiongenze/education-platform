package main

import (
	"errors"
	"os"
)

// shouldFailClosed reports whether the gateway must refuse to start when JWT
// public key loading fails. In production (APP_ENV=production) the gateway
// fails closed — anonymous WebSocket auth is never allowed. In all other
// environments the existing dev-only warning path is preserved.
//
// Extracted from main() so the production fail-closed behavior is unit
// testable without spinning up the full HTTP server.
func shouldFailClosed() bool {
	return os.Getenv("APP_ENV") == "production"
}

// failClosedMessage returns the log.Fatalf message emitted when the gateway
// refuses to start in production due to a JWT key load failure. Kept as a
// separate function so tests can assert on the wording without depending on
// log.Fatalf terminating the process.
func failClosedMessage(err error) string {
	return "FATAL: jwt public key load failed in production: " + err.Error() +
		" — refusing to start with anonymous WS auth"
}

// errFailClosed is a sentinel returned by evaluateKeyLoad when the gateway
// must fail closed. Tests check for this sentinel rather than calling
// log.Fatalf directly.
var errFailClosed = errors.New("production fail-closed: jwt public key missing")

// evaluateKeyLoad decides what to do given the result of JWT public key
// loading. It returns:
//   - (true, nil): production fail-closed — caller MUST log.Fatalf and exit.
//   - (false, nil): dev warning path — caller logs the warning and continues
//     with anonymous auth.
//
// This is the testable core of the if-err branch in main().
func evaluateKeyLoad(err error, appEnv string) (failClosed bool) {
	if appEnv == "production" {
		return true
	}
	return false
}
