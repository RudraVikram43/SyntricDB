// Package syntricdb is a database/sql driver for SyntricDB. Register it with
// the standard library and use it like any other driver:
//
//	import (
//		"database/sql"
//		_ "github.com/upendra-manike/SyntricDB/deploy/clients/go/syntricdb"
//	)
//
//	db, err := sql.Open("syntricdb", "syntricdb://admin:secret@localhost:8080/default")
//
// This works with the standard library's connection pooling and with any
// tool built on database/sql (sqlx, GORM's generic driver support, etc.).
package syntricdb

import (
	"context"
	"database/sql"
	"database/sql/driver"
)

func init() {
	sql.Register("syntricdb", &Driver{})
}

// Driver implements driver.Driver and driver.DriverContext.
type Driver struct{}

func (d *Driver) Open(dsn string) (driver.Conn, error) {
	cfg, err := parseDSN(dsn)
	if err != nil {
		return nil, err
	}
	return newConn(cfg), nil
}

func (d *Driver) OpenConnector(dsn string) (driver.Connector, error) {
	cfg, err := parseDSN(dsn)
	if err != nil {
		return nil, err
	}
	return &connector{cfg: cfg, driver: d}, nil
}

type connector struct {
	cfg    *Config
	driver *Driver
}

func (c *connector) Connect(ctx context.Context) (driver.Conn, error) {
	return newConn(c.cfg), nil
}

func (c *connector) Driver() driver.Driver {
	return c.driver
}
