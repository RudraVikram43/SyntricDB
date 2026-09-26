package syntricdb

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// Config holds parsed connection parameters.
type Config struct {
	Host     string
	Port     int
	User     string
	Password string
	Database string
}

// parseDSN accepts "syntricdb://user:pass@host:port/database" or
// "jdbc:syntricdb://...", matching the connection-string format used by the
// Java, Python, and example clients.
func parseDSN(dsn string) (*Config, error) {
	clean := strings.Replace(dsn, "jdbc:syntricdb://", "syntricdb://", 1)
	if !strings.Contains(clean, "://") {
		clean = "syntricdb://" + clean
	}
	u, err := url.Parse(clean)
	if err != nil {
		return nil, fmt.Errorf("syntricdb: invalid connection string %q: %w", dsn, err)
	}

	cfg := &Config{
		Host:     u.Hostname(),
		Port:     8080,
		User:     "admin",
		Password: "syntricdb_secret_pass",
		Database: "default",
	}
	if cfg.Host == "" {
		cfg.Host = "localhost"
	}
	if p := u.Port(); p != "" {
		port, err := strconv.Atoi(p)
		if err != nil {
			return nil, fmt.Errorf("syntricdb: invalid port %q in connection string", p)
		}
		cfg.Port = port
	}
	if u.User != nil {
		cfg.User = u.User.Username()
		if pass, ok := u.User.Password(); ok {
			cfg.Password = pass
		}
	}
	// Trim both ends: a trailing slash (".../default/") must not become part
	// of the database name.
	if db := strings.Trim(u.Path, "/"); db != "" {
		cfg.Database = db
	}
	return cfg, nil
}
