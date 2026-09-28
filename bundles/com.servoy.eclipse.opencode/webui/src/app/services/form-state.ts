import {
  FormAnswer,
  FormExternalField,
  FormField,
  FormInfo,
  FormMultiselectField,
  FormNumberField,
  FormStringField,
  FormValue,
  FormWhen
} from '../models/opencode.models';

/**
 * Dependency-free logic for the opencode V2 interactive Form exchange, mirroring
 * OpenChamber's {@code formCardState.ts} (reimplemented, not copied). Kept free
 * of Angular so it is trivially unit-testable: seed the initial answer, decide
 * which fields are visible (their {@code when} clauses), validate visible
 * fields, and build the settled answer map to POST to {@code .../reply}.
 */

/** The numeric-sentinel strings the server uses in place of non-finite numbers. */
const NUMBER_SENTINELS = new Set(['Infinity', '-Infinity', 'NaN']);

/** Result of evaluating a form against a working answer map. */
export interface FormEvaluation {
  /** Fields currently visible (their {@code when} clauses hold, not hidden). */
  visibleFields: FormField[];
  /** Per-field validation error message, keyed by field key. */
  errors: Record<string, string>;
  /** True when every visible field is valid. */
  valid: boolean;
}

/** True for the {@code external} field kind (acknowledge-only, no value). */
export function isExternalField(field: FormField): field is FormExternalField {
  return field.type === 'external';
}

/**
 * Seeds a working answer map from each field's {@code default}. Booleans default
 * to {@code false}, multiselects to {@code []}; string/number/integer fields are
 * left unset unless a {@code default} is present. External fields carry no value.
 */
export function initialAnswer(form: FormInfo): FormAnswer {
  const answer: FormAnswer = {};
  for (const field of form.fields ?? []) {
    switch (field.type) {
      case 'boolean':
        answer[field.key] = typeof field.default === 'boolean' ? field.default : false;
        break;
      case 'multiselect': {
        const def = (field as FormMultiselectField).default;
        answer[field.key] = Array.isArray(def) ? [...def] : [];
        break;
      }
      case 'number':
      case 'integer': {
        const def = (field as FormNumberField).default;
        if (def !== undefined) {
          answer[field.key] = def as FormValue;
        }
        break;
      }
      case 'string': {
        const def = (field as FormStringField).default;
        if (def !== undefined) {
          answer[field.key] = def;
        }
        break;
      }
      // external: no value
      default:
        break;
    }
  }
  return answer;
}

/** Evaluates a single {@code when} clause against the current answers. */
function clauseHolds(clause: FormWhen, answer: FormAnswer): boolean {
  const current = answer[clause.key];
  const equal = current === clause.value;
  if (clause.op === 'neq') {
    return !equal;
  }
  // Default / 'eq'.
  return equal;
}

/**
 * Whether a field is currently visible: not {@code hidden}, and all of its
 * {@code when} clauses hold (ANDed). A field with no {@code when} is always
 * visible.
 */
export function isVisible(field: FormField, answer: FormAnswer): boolean {
  if (field.hidden === true) {
    return false;
  }
  const clauses = field.when;
  if (!clauses || clauses.length === 0) {
    return true;
  }
  return clauses.every((c) => clauseHolds(c, answer));
}

/** True when a value counts as "not answered" for required-field validation. */
function isEmpty(value: FormValue | undefined): boolean {
  if (value === undefined || value === null) {
    return true;
  }
  if (typeof value === 'string') {
    return value.trim().length === 0;
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  return false;
}

/** Validates a string {@code format} constraint, returning an error or null. */
function validateFormat(format: string | undefined, value: string): string | null {
  switch (format) {
    case 'email':
      return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value) ? null : 'Enter a valid email address.';
    case 'uri':
      try {
        // eslint-disable-next-line no-new
        new URL(value);
        return null;
      } catch {
        return 'Enter a valid URL.';
      }
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(value) ? null : 'Enter a date as YYYY-MM-DD.';
    case 'date-time':
      return Number.isNaN(Date.parse(value)) ? 'Enter a valid date/time.' : null;
    default:
      return null;
  }
}

/** Validates one visible field, returning an error message or {@code null}. */
function validateField(field: FormField, answer: FormAnswer): string | null {
  const value = answer[field.key];

  if (isExternalField(field)) {
    // Acknowledged by a truthy marker in the answer map.
    if (field.required && value !== true) {
      return 'Please acknowledge this link.';
    }
    return null;
  }

  if (field.required && isEmpty(value)) {
    return 'This field is required.';
  }
  // A multiselect can carry a minItems constraint that an empty selection
  // violates even when the field is optional, so validate it before the
  // empty short-circuit below.
  if (field.type === 'multiselect') {
    const f = field as FormMultiselectField;
    const arr = Array.isArray(value) ? value : [];
    if (f.minItems != null && arr.length < f.minItems) {
      return `Select at least ${f.minItems}.`;
    }
    if (f.maxItems != null && arr.length > f.maxItems) {
      return `Select at most ${f.maxItems}.`;
    }
    return null;
  }
  // Optional + empty is fine; skip further checks.
  if (isEmpty(value)) {
    return null;
  }

  switch (field.type) {
    case 'string': {
      const str = String(value);
      const f = field as FormStringField;
      if (f.minLength != null && str.length < f.minLength) {
        return `Must be at least ${f.minLength} characters.`;
      }
      if (f.maxLength != null && str.length > f.maxLength) {
        return `Must be at most ${f.maxLength} characters.`;
      }
      if (f.pattern) {
        try {
          if (!new RegExp(f.pattern).test(str)) {
            return 'Does not match the required format.';
          }
        } catch {
          // Ignore an invalid server-side pattern rather than block submit.
        }
      }
      const formatError = validateFormat(f.format, str);
      if (formatError) {
        return formatError;
      }
      return null;
    }
    case 'number':
    case 'integer': {
      const f = field as FormNumberField;
      const num = typeof value === 'number' ? value : Number(value);
      if (Number.isNaN(num)) {
        return 'Enter a valid number.';
      }
      if (field.type === 'integer' && !Number.isInteger(num)) {
        return 'Enter a whole number.';
      }
      if (f.minimum != null && num < f.minimum) {
        return `Must be at least ${f.minimum}.`;
      }
      if (f.maximum != null && num > f.maximum) {
        return `Must be at most ${f.maximum}.`;
      }
      return null;
    }
    default:
      return null;
  }
}

/**
 * Evaluates the form: which fields are visible, per-field errors, and whether
 * the whole thing is valid. Hidden / invisible fields are skipped entirely.
 */
export function evaluateForm(form: FormInfo, answer: FormAnswer): FormEvaluation {
  const visibleFields = (form.fields ?? []).filter((f) => isVisible(f, answer));
  const errors: Record<string, string> = {};
  for (const field of visibleFields) {
    const err = validateField(field, answer);
    if (err) {
      errors[field.key] = err;
    }
  }
  return { visibleFields, errors, valid: Object.keys(errors).length === 0 };
}

/** Coerces a raw answer value to the shape the server expects for a field. */
function coerceValue(field: FormField, value: FormValue): FormValue {
  if ((field.type === 'number' || field.type === 'integer') && typeof value === 'string') {
    if (NUMBER_SENTINELS.has(value)) {
      // Preserve the server's non-finite sentinels verbatim.
      return value;
    }
    const num = Number(value);
    return Number.isNaN(num) ? value : num;
  }
  return value;
}

/**
 * Builds the settled answer map to POST to {@code .../reply}: only visible,
 * non-external fields with a set value, coercing numeric strings and preserving
 * the {@code "Infinity"}/{@code "-Infinity"}/{@code "NaN"} sentinels. Hidden /
 * invisible / external fields are excluded.
 */
export function buildFormAnswer(form: FormInfo, answer: FormAnswer): FormAnswer {
  const out: FormAnswer = {};
  for (const field of form.fields ?? []) {
    if (isExternalField(field) || !isVisible(field, answer)) {
      continue;
    }
    const value = answer[field.key];
    if (isEmpty(value) && field.type !== 'boolean') {
      continue;
    }
    out[field.key] = coerceValue(field, value as FormValue);
  }
  return out;
}
