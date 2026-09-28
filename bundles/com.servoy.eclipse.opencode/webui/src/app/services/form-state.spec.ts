import { describe, expect, it } from 'vitest';

import { FormInfo } from '../models/opencode.models';
import {
  buildFormAnswer,
  evaluateForm,
  initialAnswer,
  isExternalField,
  isVisible
} from './form-state';

/**
 * Pure-logic tests for the opencode V2 Form exchange helpers - the seeding,
 * {@code when}-clause visibility, validation, and answer-building that decide
 * whether the user can submit and what gets POSTed to {@code .../reply}.
 */

function form(fields: FormInfo['fields'], title = 'Q'): FormInfo {
  return { id: 'frm_1', sessionID: 'ses_1', title, fields };
}

describe('form-state', () => {
  describe('initialAnswer', () => {
    it('seeds booleans to false when no default, multiselects to []', () => {
      const f = form([
        { key: 'b', type: 'boolean' },
        { key: 'm', type: 'multiselect', options: [{ value: 'a', label: 'A' }] }
      ]);
      expect(initialAnswer(f)).toEqual({ b: false, m: [] });
    });

    it('applies boolean/multiselect/string/number defaults', () => {
      const f = form([
        { key: 'b', type: 'boolean', default: true },
        {
          key: 'm',
          type: 'multiselect',
          options: [{ value: 'a', label: 'A' }],
          default: ['a']
        },
        { key: 's', type: 'string', default: 'hi' },
        { key: 'n', type: 'number', default: 5 }
      ]);
      expect(initialAnswer(f)).toEqual({ b: true, m: ['a'], s: 'hi', n: 5 });
    });

    it('leaves string/number without a default unset, and external unset', () => {
      const f = form([
        { key: 's', type: 'string' },
        { key: 'n', type: 'number' },
        { key: 'e', type: 'external', url: 'https://x' }
      ]);
      expect(initialAnswer(f)).toEqual({});
    });
  });

  describe('isVisible / when clauses', () => {
    it('is visible with no when clause and hidden when hidden:true', () => {
      const shown = { key: 's', type: 'string' as const };
      const hidden = { key: 'h', type: 'string' as const, hidden: true };
      expect(isVisible(shown, {})).toBe(true);
      expect(isVisible(hidden, {})).toBe(false);
    });

    it('evaluates eq / neq clauses (ANDed) against the current answer', () => {
      const eqField = {
        key: 'x',
        type: 'string' as const,
        when: [{ key: 'mode', op: 'eq', value: 'advanced' }]
      };
      expect(isVisible(eqField, { mode: 'advanced' })).toBe(true);
      expect(isVisible(eqField, { mode: 'basic' })).toBe(false);

      const neqField = {
        key: 'y',
        type: 'string' as const,
        when: [{ key: 'mode', op: 'neq', value: 'basic' }]
      };
      expect(isVisible(neqField, { mode: 'advanced' })).toBe(true);
      expect(isVisible(neqField, { mode: 'basic' })).toBe(false);
    });

    it('requires ALL clauses to hold (AND)', () => {
      const field = {
        key: 'x',
        type: 'string' as const,
        when: [
          { key: 'a', op: 'eq', value: '1' },
          { key: 'b', op: 'eq', value: '2' }
        ]
      };
      expect(isVisible(field, { a: '1', b: '2' })).toBe(true);
      expect(isVisible(field, { a: '1', b: 'x' })).toBe(false);
    });
  });

  describe('evaluateForm', () => {
    it('flags a required visible field that is empty', () => {
      const f = form([{ key: 's', type: 'string', required: true }]);
      const result = evaluateForm(f, {});
      expect(result.valid).toBe(false);
      expect(result.errors['s']).toBeTruthy();
    });

    it('is valid when a required field is answered', () => {
      const f = form([{ key: 's', type: 'string', required: true }]);
      const result = evaluateForm(f, { s: 'answer' });
      expect(result.valid).toBe(true);
      expect(result.errors).toEqual({});
    });

    it('skips invisible fields entirely (not in visibleFields, not required-checked)', () => {
      const f = form([
        { key: 'mode', type: 'string', options: [{ value: 'basic', label: 'B' }] },
        {
          key: 'extra',
          type: 'string',
          required: true,
          when: [{ key: 'mode', op: 'eq', value: 'advanced' }]
        }
      ]);
      // mode is 'basic' so 'extra' is hidden -> its required-ness must not block.
      const result = evaluateForm(f, { mode: 'basic' });
      expect(result.visibleFields.map((x) => x.key)).toEqual(['mode']);
      expect(result.valid).toBe(true);
    });

    it('enforces string minLength / maxLength / pattern', () => {
      const f = form([
        { key: 's', type: 'string', minLength: 3, maxLength: 5, pattern: '^[a-z]+$' }
      ]);
      expect(evaluateForm(f, { s: 'ab' }).errors['s']).toBeTruthy();
      expect(evaluateForm(f, { s: 'abcdef' }).errors['s']).toBeTruthy();
      expect(evaluateForm(f, { s: 'AB1' }).errors['s']).toBeTruthy();
      expect(evaluateForm(f, { s: 'abc' }).valid).toBe(true);
    });

    it('enforces number minimum / maximum and integer-ness', () => {
      const num = form([{ key: 'n', type: 'number', minimum: 1, maximum: 10 }]);
      expect(evaluateForm(num, { n: 0 }).errors['n']).toBeTruthy();
      expect(evaluateForm(num, { n: 11 }).errors['n']).toBeTruthy();
      expect(evaluateForm(num, { n: 5 }).valid).toBe(true);

      const int = form([{ key: 'i', type: 'integer' }]);
      expect(evaluateForm(int, { i: 1.5 }).errors['i']).toBeTruthy();
      expect(evaluateForm(int, { i: 2 }).valid).toBe(true);
    });

    it('enforces multiselect minItems / maxItems', () => {
      const f = form([
        {
          key: 'm',
          type: 'multiselect',
          options: [
            { value: 'a', label: 'A' },
            { value: 'b', label: 'B' }
          ],
          minItems: 1,
          maxItems: 1
        }
      ]);
      expect(evaluateForm(f, { m: [] }).errors['m']).toBeTruthy();
      expect(evaluateForm(f, { m: ['a', 'b'] }).errors['m']).toBeTruthy();
      expect(evaluateForm(f, { m: ['a'] }).valid).toBe(true);
    });

    it('requires acknowledgement of a required external field', () => {
      const f = form([{ key: 'e', type: 'external', url: 'https://x', required: true }]);
      expect(evaluateForm(f, {}).errors['e']).toBeTruthy();
      expect(evaluateForm(f, { e: true }).valid).toBe(true);
    });

    it('validates string formats (email/uri/date)', () => {
      const email = form([{ key: 's', type: 'string', format: 'email' }]);
      expect(evaluateForm(email, { s: 'nope' }).errors['s']).toBeTruthy();
      expect(evaluateForm(email, { s: 'a@b.co' }).valid).toBe(true);

      const uri = form([{ key: 's', type: 'string', format: 'uri' }]);
      expect(evaluateForm(uri, { s: 'not a url' }).errors['s']).toBeTruthy();
      expect(evaluateForm(uri, { s: 'https://x.io' }).valid).toBe(true);

      const date = form([{ key: 's', type: 'string', format: 'date' }]);
      expect(evaluateForm(date, { s: '01/02/2026' }).errors['s']).toBeTruthy();
      expect(evaluateForm(date, { s: '2026-01-02' }).valid).toBe(true);
    });

    it('does not error an optional empty field', () => {
      const f = form([{ key: 's', type: 'string', minLength: 3 }]);
      expect(evaluateForm(f, {}).valid).toBe(true);
    });
  });

  describe('buildFormAnswer', () => {
    it('includes only visible, non-external fields', () => {
      const f = form([
        { key: 's', type: 'string' },
        { key: 'e', type: 'external', url: 'https://x' },
        {
          key: 'extra',
          type: 'string',
          when: [{ key: 's', op: 'eq', value: 'show' }]
        }
      ]);
      // extra is hidden (s !== 'show') and external is always excluded.
      const answer = buildFormAnswer(f, { s: 'hi', e: true, extra: 'nope' });
      expect(answer).toEqual({ s: 'hi' });
    });

    it('coerces numeric strings to numbers but preserves the sentinels', () => {
      const f = form([
        { key: 'n', type: 'number' },
        { key: 'inf', type: 'number' },
        { key: 'nan', type: 'number' }
      ]);
      const answer = buildFormAnswer(f, { n: '42', inf: 'Infinity', nan: 'NaN' });
      expect(answer).toEqual({ n: 42, inf: 'Infinity', nan: 'NaN' });
    });

    it('keeps a boolean even when false, drops unset optional strings', () => {
      const f = form([
        { key: 'b', type: 'boolean' },
        { key: 's', type: 'string' }
      ]);
      const answer = buildFormAnswer(f, { b: false });
      expect(answer).toEqual({ b: false });
    });

    it('carries multiselect arrays including custom (non-option) values', () => {
      const f = form([
        {
          key: 'm',
          type: 'multiselect',
          options: [{ value: 'a', label: 'A' }],
          custom: true
        }
      ]);
      const answer = buildFormAnswer(f, { m: ['a', 'my-own'] });
      expect(answer).toEqual({ m: ['a', 'my-own'] });
    });
  });

  describe('isExternalField', () => {
    it('narrows on the external type', () => {
      expect(isExternalField({ key: 'e', type: 'external', url: 'x' })).toBe(true);
      expect(isExternalField({ key: 's', type: 'string' })).toBe(false);
    });
  });
});
