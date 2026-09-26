// Package jwt provides RS256 JWT validation utilities shared across
// education-platform Go services. Extracted from ws-gateway (D-109).
//
// Concurrency: a *Validator is immutable after construction and therefore
// safe for concurrent use by multiple goroutines without synchronization.
package jwt

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// D-117: default file paths for JWT keys mounted from a K8s Secret.
const (
	DefaultPublicKeyPath  = "/etc/jwt/public.pem"
	DefaultPrivateKeyPath = "/etc/jwt/private.pem"
)

// Validator validates RS256 JWT tokens and extracts standard + app claims.
// The embedded public key is never mutated, so a *Validator may be shared
// freely across goroutines.
type Validator struct {
	pub *rsa.PublicKey
}

// Claims is the set of claims ep services care about. It embeds the standard
// RegisteredClaims from jwt/v5 (sub, iss, exp, iat, nbf, ...) and adds the
// application-specific role claim used across the platform.
type Claims struct {
	Subject string `json:"sub,omitempty"`
	Role    string `json:"role,omitempty"`
	Issuer  string `json:"iss,omitempty"`
}

// NewValidator creates a Validator from a PEM-encoded RSA public key.
// Returns an error if the PEM is malformed or the key is not RSA.
func NewValidator(publicKeyPEM string) (*Validator, error) {
	if publicKeyPEM == "" {
		return nil, fmt.Errorf("jwt: empty public key PEM")
	}
	return parsePEM(publicKeyPEM)
}

// NewValidatorFromPEM is an alias for NewValidator kept for compatibility
// with ws-gateway's historical naming.
func NewValidatorFromPEM(pemStr string) (*Validator, error) {
	return NewValidator(pemStr)
}

func parsePEM(pemStr string) (*Validator, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("jwt: bad PEM, no block found")
	}
	pubAny, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		// PKCS#1 public keys are sometimes seen; try that as a fallback.
		if pub1, err1 := x509.ParsePKCS1PublicKey(block.Bytes); err1 == nil {
			return &Validator{pub: pub1}, nil
		}
		return nil, fmt.Errorf("jwt: parse PKIX: %w", err)
	}
	pub, ok := pubAny.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("jwt: key is not an RSA public key")
	}
	return &Validator{pub: pub}, nil
}

// Validate parses and validates the token using RS256 and the configured
// public key, then returns the platform Claims. A 30s leeway is allowed to
// tolerate minor clock skew between services (matches ws-gateway behavior).
//
// On any validation failure an error is returned; callers that only need a
// boolean may use the older ParseUserID/ParseRole helpers instead.
func (v *Validator) Validate(tokenString string) (*Claims, error) {
	if tokenString == "" {
		return nil, fmt.Errorf("jwt: empty token")
	}
	tok, err := jwt.Parse(tokenString, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, fmt.Errorf("jwt: unexpected signing method: %v", t.Header["alg"])
		}
		return v.pub, nil
	}, jwt.WithLeeway(30*time.Second))
	if err != nil {
		return nil, fmt.Errorf("jwt: parse: %w", err)
	}
	if !tok.Valid {
		return nil, fmt.Errorf("jwt: token not valid")
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok {
		return nil, fmt.Errorf("jwt: unexpected claims type")
	}
	c := &Claims{}
	if sub, err := claims.GetSubject(); err == nil {
		c.Subject = sub
	}
	if iss, err := claims.GetIssuer(); err == nil {
		c.Issuer = iss
	}
	if role, ok := claims["role"].(string); ok {
		c.Role = role
	}
	return c, nil
}

// ParseUserID validates the token and returns the user_id parsed from the
// subject claim. Returns 0 if the token is invalid, malformed, or the
// subject is not an integer. Kept for backwards compatibility with ws-gateway.
func (v *Validator) ParseUserID(tokenStr string) int64 {
	c, err := v.Validate(tokenStr)
	if err != nil || c == nil {
		return 0
	}
	uid, _ := strconv.ParseInt(c.Subject, 10, 64)
	return uid
}

// ParseRole validates the token and returns the role claim, or "" on failure.
// Kept for backwards compatibility with ws-gateway.
func (v *Validator) ParseRole(tokenStr string) string {
	c, err := v.Validate(tokenStr)
	if err != nil || c == nil {
		return ""
	}
	return c.Role
}

// ---------------------------------------------------------------------------
// D-117: key externalization helpers
// ---------------------------------------------------------------------------

// LoadPublicKeyPEM resolves the RS256 public key PEM using the D-117 priority:
//  1. JWT_PUBLIC_KEY_PATH file path (default: /etc/jwt/public.pem)
//  2. JWT_PUBLIC_KEY base64-encoded inline env var
//  3. JWT_RSA_PUBLIC_KEY_PEM legacy inline PEM env var (dev backwards-compat)
//
// Returns the PEM string, a short human-readable source label (e.g.
// "file /etc/jwt/public.pem", "env JWT_PUBLIC_KEY", "env JWT_RSA_PUBLIC_KEY_PEM"),
// and any error. If no source is available the error message explains how to
// configure the key — it is never silently empty.
func LoadPublicKeyPEM() (string, string, error) {
	return loadKeyPEM("JWT_PUBLIC_KEY_PATH", DefaultPublicKeyPath,
		"JWT_PUBLIC_KEY", "JWT_RSA_PUBLIC_KEY_PEM", "public key")
}

// LoadPrivateKeyPEM resolves the RS256 private key PEM using the D-117 priority:
//  1. JWT_PRIVATE_KEY_PATH file path (default: /etc/jwt/private.pem)
//  2. JWT_PRIVATE_KEY base64-encoded inline env var
//  3. JWT_RSA_PRIVATE_KEY_PEM legacy inline PEM env var (dev backwards-compat)
//
// Returns the PEM string, a short human-readable source label, and any error.
func LoadPrivateKeyPEM() (string, string, error) {
	return loadKeyPEM("JWT_PRIVATE_KEY_PATH", DefaultPrivateKeyPath,
		"JWT_PRIVATE_KEY", "JWT_RSA_PRIVATE_KEY_PEM", "private key")
}

func loadKeyPEM(pathEnv, defaultPath, base64Env, legacyEnv, label string) (string, string, error) {
	// 1) File path
	path := os.Getenv(pathEnv)
	if path == "" {
		path = defaultPath
	}
	if data, err := os.ReadFile(path); err == nil {
		s := string(data)
		if s != "" {
			return s, "file " + path, nil
		}
	}

	// 2) base64 inline
	if b64 := os.Getenv(base64Env); b64 != "" {
		decoded, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			return "", "", fmt.Errorf("jwt: env %s set but not valid base64: %w", base64Env, err)
		}
		return string(decoded), "env " + base64Env, nil
	}

	// 3) legacy inline PEM (dev backwards-compat)
	if pem := os.Getenv(legacyEnv); pem != "" {
		return pem, "env " + legacyEnv, nil
	}

	return "", "", fmt.Errorf(
		"jwt: %s not found. Configure one of: %s (file, default %s), %s (base64 inline), %s (legacy PEM inline). See D-117.",
		label, pathEnv, defaultPath, base64Env, legacyEnv)
}
