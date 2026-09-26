"""PEP 249 (DB-API 2.0) type constructors and type-comparison singletons."""

import datetime
import time as _time

Date = datetime.date
Time = datetime.time
Timestamp = datetime.datetime


def DateFromTicks(ticks):
    return Date(*_time.localtime(ticks)[:3])


def TimeFromTicks(ticks):
    return Time(*_time.localtime(ticks)[3:6])


def TimestampFromTicks(ticks):
    return Timestamp(*_time.localtime(ticks)[:6])


def Binary(data):
    return bytes(data)


class _DBAPITypeObject:
    """Compares equal to any of the Python types it was built from, per PEP 249."""

    def __init__(self, *values):
        self.values = values

    def __eq__(self, other):
        return other in self.values

    def __hash__(self):
        return hash(self.values)


STRING = _DBAPITypeObject(str)
BINARY = _DBAPITypeObject(bytes, bytearray)
NUMBER = _DBAPITypeObject(int, float)
DATETIME = _DBAPITypeObject(datetime.date, datetime.time, datetime.datetime)
ROWID = _DBAPITypeObject()
