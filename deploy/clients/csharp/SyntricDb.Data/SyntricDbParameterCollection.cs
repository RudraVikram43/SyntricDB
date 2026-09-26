using System;
using System.Collections;
using System.Collections.Generic;
using System.Data.Common;

namespace SyntricDB.Data;

public sealed class SyntricDbParameterCollection : DbParameterCollection
{
    private readonly List<SyntricDbParameter> _items = new();

    public override int Count => _items.Count;
    public override object SyncRoot { get; } = new object();
    public override bool IsFixedSize => false;
    public override bool IsReadOnly => false;
    public override bool IsSynchronized => false;

    public override int Add(object value)
    {
        _items.Add(RequireParameter(value));
        return _items.Count - 1;
    }

    public override void AddRange(Array values)
    {
        foreach (object? v in values)
        {
            if (v != null) Add(v);
        }
    }

    public override void Clear() => _items.Clear();

    public override bool Contains(object value) => value is SyntricDbParameter p && _items.Contains(p);

    public override bool Contains(string value) => IndexOf(value) >= 0;

    public override void CopyTo(Array array, int index)
    {
        for (int i = 0; i < _items.Count; i++) array.SetValue(_items[i], index + i);
    }

    public override IEnumerator GetEnumerator() => _items.GetEnumerator();

    public override int IndexOf(object value) => value is SyntricDbParameter p ? _items.IndexOf(p) : -1;

    public override int IndexOf(string parameterName)
    {
        for (int i = 0; i < _items.Count; i++)
        {
            if (string.Equals(_items[i].ParameterName, parameterName, StringComparison.OrdinalIgnoreCase)) return i;
        }
        return -1;
    }

    public override void Insert(int index, object value) => _items.Insert(index, RequireParameter(value));

    public override void Remove(object value)
    {
        if (value is SyntricDbParameter p) _items.Remove(p);
    }

    public override void RemoveAt(int index) => _items.RemoveAt(index);

    public override void RemoveAt(string parameterName)
    {
        int i = IndexOf(parameterName);
        if (i >= 0) RemoveAt(i);
    }

    protected override DbParameter GetParameter(int index) => _items[index];

    protected override DbParameter GetParameter(string parameterName)
    {
        int i = IndexOf(parameterName);
        if (i < 0) throw new IndexOutOfRangeException($"Parameter '{parameterName}' not found.");
        return _items[i];
    }

    protected override void SetParameter(int index, DbParameter value) => _items[index] = RequireParameter(value);

    protected override void SetParameter(string parameterName, DbParameter value)
    {
        int i = IndexOf(parameterName);
        if (i < 0) throw new IndexOutOfRangeException($"Parameter '{parameterName}' not found.");
        _items[i] = RequireParameter(value);
    }

    /// <summary>Parameters bind to '?' placeholders positionally, in Add() order.</summary>
    internal IReadOnlyList<SyntricDbParameter> Ordered => _items;

    private static SyntricDbParameter RequireParameter(object value)
    {
        if (value is SyntricDbParameter p) return p;
        throw new ArgumentException($"Expected a {nameof(SyntricDbParameter)}, got {value.GetType()}.", nameof(value));
    }
}
