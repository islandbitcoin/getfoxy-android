'use strict';
/* schema.js — the small type language contract.json writes payloads in.
 *
 * A payload schema is { "fields": { name: type, ... }, "extra": false }.
 * A type is one or more of
 *
 *     string  boolean  number  integer  object  array  null  any  string[]
 *
 * joined with "|", and a trailing "?" makes the field optional. "extra": false
 * (the default) means a field the schema does not name is a failure: the page
 * sending something new is exactly the drift this suite exists to catch.
 *
 * No JSON-Schema library on purpose: the suite's only dependency is jsdom,
 * pinned to the iOS repository's version, and this is forty lines.
 */

const BASE = new Set(['string', 'boolean', 'number', 'integer', 'object', 'array', 'null', 'any', 'string[]']);

function parseType(t) {
  const s = String(t);
  const optional = s.endsWith('?');
  const parts = (optional ? s.slice(0, -1) : s).split('|');
  for (const p of parts) if (!BASE.has(p)) throw new Error('schema: unknown type "' + p + '" in "' + s + '"');
  return { optional, parts };
}

function isType(v, p) {
  switch (p) {
    case 'any': return true;
    case 'null': return v === null;
    case 'string': return typeof v === 'string';
    case 'boolean': return typeof v === 'boolean';
    case 'number': return typeof v === 'number' && Number.isFinite(v);
    case 'integer': return Number.isInteger(v);
    case 'array': return Array.isArray(v);
    case 'string[]': return Array.isArray(v) && v.every((x) => typeof x === 'string');
    case 'object': return v !== null && typeof v === 'object' && !Array.isArray(v);
    default: return false;
  }
}

/* The problems with `value` against `schema`, as strings; [] when it fits. */
function check(schema, value) {
  const problems = [];
  if (!schema || !schema.fields) return ['no schema'];
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return ['not an object'];
  for (const [name, t] of Object.entries(schema.fields)) {
    const { optional, parts } = parseType(t);
    if (!(name in value) || value[name] === undefined) {
      if (!optional) problems.push('missing "' + name + '" (' + t + ')');
      continue;
    }
    if (!parts.some((p) => isType(value[name], p))) {
      problems.push('"' + name + '" is ' + describe(value[name]) + ', not ' + t);
    }
  }
  if (schema.extra !== true) {
    for (const name of Object.keys(value)) {
      if (!(name in schema.fields) && value[name] !== undefined) problems.push('unexpected field "' + name + '"');
    }
  }
  return problems;
}

function describe(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'an array';
  return typeof v === 'object' ? 'an object' : typeof v + ' ' + JSON.stringify(v).slice(0, 40);
}

/* Throws if a schema is not written in this language. */
function validateSchema(schema) {
  if (!schema || typeof schema !== 'object' || !schema.fields || typeof schema.fields !== 'object') {
    throw new Error('schema: needs a "fields" object');
  }
  for (const t of Object.values(schema.fields)) parseType(t);
  for (const k of Object.keys(schema)) {
    if (k !== 'fields' && k !== 'extra') throw new Error('schema: unknown key "' + k + '"');
  }
}

module.exports = { check, validateSchema, parseType };
