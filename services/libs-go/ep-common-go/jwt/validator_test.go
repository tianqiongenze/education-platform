package jwt

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"

	"github.com/golang-jwt/jwt/v5"
)

// Helpers lifted from ws-gateway's test patterns so the suite stays
// self-contained and deterministic.

func genKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func encodePEMPublic(pub *rsa.PublicKey) string {
	der, _ := x509.MarshalPKIXPublicKey(pub)
	block := &pem.Block{Type: "PUBLIC KEY", Bytes: der}
	return string(pem.EncodeToMemory(block))
}

func sign(t *testing.T, key *rsa.PrivateKey, claims jwt.MapClaims) string {
	t.Helper()
	tok, err := jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return tok
}

func TestNewValidatorParsesPEM(t *testing.T) {
	v, err := NewValidator(encodePEMPublic(&genKey(t).PublicKey))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if v == nil {
		t.Fatal("nil validator")
	}
}

func TestNewValidatorRejectsEmpty(t *testing.T) {
	if _, err := NewValidator(""); err == nil {
		t.Fatal("expected error for empty PEM")
	}
}

func TestNewValidatorRejectsGarbage(t *testing.T) {
	if _, err := NewValidator("garbage"); err == nil {
		t.Fatal("expected error for bad PEM")
	}
}

func TestNewValidatorFromPEMAlias(t *testing.T) {
	if _, err := NewValidatorFromPEM(encodePEMPublic(&genKey(t).PublicKey)); err != nil {
		t.Fatalf("alias should work: %v", err)
	}
}

func TestValidateReturnsClaims(t *testing.T) {
	key := genKey(t)
	tok := sign(t, key, jwt.MapClaims{
		"sub":  "777",
		"role": "student",
		"iss":  "ep-auth",
	})
	v, _ := NewValidator(encodePEMPublic(&key.PublicKey))
	c, err := v.Validate(tok)
	if err != nil {
		t.Fatalf("validate failed: %v", err)
	}
	if c.Subject != "777" {
		t.Errorf("subject: want 777 got %s", c.Subject)
	}
	if c.Role != "student" {
		t.Errorf("role: want student got %s", c.Role)
	}
	if c.Issuer != "ep-auth" {
		t.Errorf("issuer: want ep-auth got %s", c.Issuer)
	}
}

func TestValidateRejectsEmptyToken(t *testing.T) {
	v, _ := NewValidator(encodePEMPublic(&genKey(t).PublicKey))
	if _, err := v.Validate(""); err == nil {
		t.Fatal("expected error for empty token")
	}
}

func TestValidateRejectsWrongKey(t *testing.T) {
	signingKey := genKey(t)
	tok := sign(t, signingKey, jwt.MapClaims{"sub": "1"})
	otherKey := genKey(t)
	v, _ := NewValidator(encodePEMPublic(&otherKey.PublicKey))
	if _, err := v.Validate(tok); err == nil {
		t.Fatal("expected validation error for mismatched key")
	}
}

func TestValidateRejectsBadToken(t *testing.T) {
	v, _ := NewValidator(encodePEMPublic(&genKey(t).PublicKey))
	if _, err := v.Validate("garbage"); err == nil {
		t.Fatal("expected error for garbage token")
	}
}

// Backwards-compat helpers used by ws-gateway.

func TestParseUserID(t *testing.T) {
	key := genKey(t)
	tok := sign(t, key, jwt.MapClaims{"sub": "777", "role": "student"})
	v, _ := NewValidator(encodePEMPublic(&key.PublicKey))
	if uid := v.ParseUserID(tok); uid != 777 {
		t.Fatalf("want 777 got %d", uid)
	}
}

func TestParseUserIDReturnsZeroOnBad(t *testing.T) {
	v, _ := NewValidator(encodePEMPublic(&genKey(t).PublicKey))
	if uid := v.ParseUserID("garbage"); uid != 0 {
		t.Fatalf("want 0 got %d", uid)
	}
}

func TestParseUserIDReturnsZeroOnEmpty(t *testing.T) {
	v, _ := NewValidator(encodePEMPublic(&genKey(t).PublicKey))
	if uid := v.ParseUserID(""); uid != 0 {
		t.Fatalf("want 0 got %d", uid)
	}
}

func TestParseRole(t *testing.T) {
	key := genKey(t)
	tok := sign(t, key, jwt.MapClaims{"sub": "42", "role": "teacher"})
	v, _ := NewValidator(encodePEMPublic(&key.PublicKey))
	if role := v.ParseRole(tok); role != "teacher" {
		t.Fatalf("want teacher got %s", role)
	}
}

func TestParseRoleEmptyOnInvalid(t *testing.T) {
	v, _ := NewValidator(encodePEMPublic(&genKey(t).PublicKey))
	if role := v.ParseRole("bad"); role != "" {
		t.Fatalf("want empty got %s", role)
	}
}

// ---------------------------------------------------------------------------
// D-117: key externalization (LoadPublicKeyPEM / LoadPrivateKeyPEM)
// ---------------------------------------------------------------------------

func TestLoadPublicKeyPEMFromFile(t *testing.T) {
	pub := encodePEMPublic(&genKey(t).PublicKey)
	dir := t.TempDir()
	path := filepath.Join(dir, "public.pem")
	if err := os.WriteFile(path, []byte(pub), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("JWT_PUBLIC_KEY_PATH", path)
	t.Setenv("JWT_PUBLIC_KEY", "")
	t.Setenv("JWT_RSA_PUBLIC_KEY_PEM", "")

	got, src, err := LoadPublicKeyPEM()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != pub {
		t.Fatalf("PEM mismatch")
	}
	if src != "file "+path {
		t.Fatalf("source: want file %s got %s", path, src)
	}
}

func TestLoadPublicKeyPEMFromBase64(t *testing.T) {
	pub := encodePEMPublic(&genKey(t).PublicKey)
	t.Setenv("JWT_PUBLIC_KEY_PATH", "/nonexistent/d117-pub.pem")
	t.Setenv("JWT_PUBLIC_KEY", base64.StdEncoding.EncodeToString([]byte(pub)))
	t.Setenv("JWT_RSA_PUBLIC_KEY_PEM", "")

	got, src, err := LoadPublicKeyPEM()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != pub {
		t.Fatalf("PEM mismatch")
	}
	if src != "env JWT_PUBLIC_KEY" {
		t.Fatalf("source: want env JWT_PUBLIC_KEY got %s", src)
	}
}

func TestLoadPublicKeyPEMLegacyFallback(t *testing.T) {
	pub := encodePEMPublic(&genKey(t).PublicKey)
	t.Setenv("JWT_PUBLIC_KEY_PATH", "/nonexistent/d117-pub.pem")
	t.Setenv("JWT_PUBLIC_KEY", "")
	t.Setenv("JWT_RSA_PUBLIC_KEY_PEM", pub)

	got, src, err := LoadPublicKeyPEM()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != pub {
		t.Fatalf("PEM mismatch")
	}
	if src != "env JWT_RSA_PUBLIC_KEY_PEM" {
		t.Fatalf("source: want env JWT_RSA_PUBLIC_KEY_PEM got %s", src)
	}
}

func TestLoadPublicKeyPEMAllMissingErrors(t *testing.T) {
	t.Setenv("JWT_PUBLIC_KEY_PATH", "/nonexistent/d117-pub.pem")
	t.Setenv("JWT_PUBLIC_KEY", "")
	t.Setenv("JWT_RSA_PUBLIC_KEY_PEM", "")

	if _, _, err := LoadPublicKeyPEM(); err == nil {
		t.Fatal("expected error when no key source configured")
	}
}

func TestLoadPrivateKeyPEMFromFile(t *testing.T) {
	key := genKey(t)
	der := x509.MarshalPKCS1PrivateKey(key)
	block := &pem.Block{Type: "RSA PRIVATE KEY", Bytes: der}
	// Use PKCS8 to match production (gen-rs256-keys.sh outputs PKCS#8).
	der8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	block8 := &pem.Block{Type: "PRIVATE KEY", Bytes: der8}
	privPem := string(pem.EncodeToMemory(block8))
	_ = block

	dir := t.TempDir()
	path := filepath.Join(dir, "private.pem")
	if err := os.WriteFile(path, []byte(privPem), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("JWT_PRIVATE_KEY_PATH", path)
	t.Setenv("JWT_PRIVATE_KEY", "")
	t.Setenv("JWT_RSA_PRIVATE_KEY_PEM", "")

	got, src, err := LoadPrivateKeyPEM()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != privPem {
		t.Fatalf("PEM mismatch")
	}
	if src != "file "+path {
		t.Fatalf("source: want file %s got %s", path, src)
	}
}
