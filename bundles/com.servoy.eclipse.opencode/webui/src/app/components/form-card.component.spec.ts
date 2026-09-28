import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { FormCardComponent } from './form-card.component';
import { FormAnswer, FormInfo } from '../models/opencode.models';

/**
 * Component tests for the interactive form card - the UI that renders an
 * opencode V2 form and emits the settled answer. Asserts on the rendered DOM
 * and the emitted outputs.
 */
describe('FormCardComponent', () => {
  let fixture: ComponentFixture<FormCardComponent>;
  let component: FormCardComponent;

  async function setForm(form: FormInfo): Promise<void> {
    await TestBed.configureTestingModule({ imports: [FormCardComponent] }).compileComponents();
    fixture = TestBed.createComponent(FormCardComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('form', form);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function submitBtn(): HTMLButtonElement {
    return (fixture.nativeElement as HTMLElement).querySelector('button.submit')!;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('renders the title and the option buttons for a string-with-options field', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Fruit?',
      fields: [
        {
          key: 'q0',
          type: 'string',
          title: 'Pick one',
          options: [
            { value: 'Apple', label: 'Apple' },
            { value: 'Banana', label: 'Banana' }
          ]
        }
      ]
    });

    expect(text()).toContain('Fruit?');
    const options = (fixture.nativeElement as HTMLElement).querySelectorAll('button.option');
    expect(options).toHaveLength(2);
  });

  it('disables submit until a required field is answered, then enables it', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [{ key: 'name', type: 'string', required: true }]
    });

    expect(submitBtn().disabled).toBe(true);

    component.onStringInput({ key: 'name', type: 'string', required: true }, 'Jo');
    fixture.detectChanges();

    expect(submitBtn().disabled).toBe(false);
  });

  it('emits the selected option value on submit', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [
        {
          key: 'q0',
          type: 'string',
          required: true,
          options: [
            { value: 'Apple', label: 'Apple' },
            { value: 'Banana', label: 'Banana' }
          ]
        }
      ]
    });

    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));

    component.selectOption(
      { key: 'q0', type: 'string', options: [] },
      { value: 'Banana', label: 'Banana' }
    );
    fixture.detectChanges();
    component.onSubmit();

    expect(emitted).toEqual({ q0: 'Banana' });
  });

  it('supports a custom free-text entry alongside options', async () => {
    const field = {
      key: 'q0',
      type: 'string' as const,
      options: [{ value: 'Apple', label: 'Apple' }],
      custom: true
    };
    await setForm({ id: 'frm_1', sessionID: 's1', title: 'Q', fields: [field] });

    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));

    component.onStringInput(field, 'Kiwi');
    fixture.detectChanges();
    component.onSubmit();

    expect(emitted).toEqual({ q0: 'Kiwi' });
  });

  it('hides a field whose when clause is not met and omits it from the answer', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [
        {
          key: 'mode',
          type: 'string',
          required: true,
          options: [
            { value: 'basic', label: 'Basic' },
            { value: 'advanced', label: 'Advanced' }
          ]
        },
        {
          key: 'extra',
          type: 'string',
          required: true,
          when: [{ key: 'mode', op: 'eq', value: 'advanced' }]
        }
      ]
    });

    // Only 'mode' is visible while it is unset/basic.
    let visibleKeys = component.evaluation().visibleFields.map((f) => f.key);
    expect(visibleKeys).toEqual(['mode']);

    component.selectOption({ key: 'mode', type: 'string', options: [] }, {
      value: 'advanced',
      label: 'Advanced'
    });
    fixture.detectChanges();

    visibleKeys = component.evaluation().visibleFields.map((f) => f.key);
    expect(visibleKeys).toEqual(['mode', 'extra']);
    // 'extra' now required and empty -> submit disabled.
    expect(submitBtn().disabled).toBe(true);
  });

  it('toggles multiselect options and can add + remove a custom value', async () => {
    const field = {
      key: 'm',
      type: 'multiselect' as const,
      options: [
        { value: 'a', label: 'A' },
        { value: 'b', label: 'B' }
      ],
      custom: true
    };
    await setForm({ id: 'frm_1', sessionID: 's1', title: 'Q', fields: [field] });

    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));

    component.toggleMultiOption(field, { value: 'a', label: 'A' }, true);
    component.onCustomDraftInput(field, 'zzz');
    component.addCustomValue(field);
    fixture.detectChanges();

    expect(component.customSelections(field)).toEqual(['zzz']);

    component.onSubmit();
    expect(emitted).toEqual({ m: ['a', 'zzz'] });

    component.removeCustomValue(field, 'zzz');
    expect(component.customSelections(field)).toEqual([]);
  });

  it('renders a boolean checkbox and includes it in the answer', async () => {
    const field = { key: 'agree', type: 'boolean' as const, title: 'Agree?' };
    await setForm({ id: 'frm_1', sessionID: 's1', title: 'Q', fields: [field] });

    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));

    component.onBooleanToggle(field, true);
    fixture.detectChanges();
    component.onSubmit();

    expect(emitted).toEqual({ agree: true });
  });

  it('requires acknowledging an external field before submit', async () => {
    const field = {
      key: 'e',
      type: 'external' as const,
      url: 'https://example.com',
      required: true
    };
    await setForm({ id: 'frm_1', sessionID: 's1', title: 'Q', fields: [field] });

    expect(submitBtn().disabled).toBe(true);

    component.acknowledgeExternal(field);
    fixture.detectChanges();

    expect(submitBtn().disabled).toBe(false);
    // External fields carry no value in the submitted answer.
    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));
    component.onSubmit();
    expect(emitted).toEqual({});
  });

  it('labels a required field with a marker and wires the error aria-describedby', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [{ key: 'name', type: 'string', title: 'Name', required: true, minLength: 3 }]
    });

    const host = fixture.nativeElement as HTMLElement;
    // Required marker rendered.
    expect(host.querySelector('.required')).not.toBeNull();

    // Type an invalid (too short) value so an error renders.
    const field = { key: 'name', type: 'string' as const, required: true, minLength: 3 };
    component.onStringInput(field, 'ab');
    fixture.detectChanges();

    const input = host.querySelector<HTMLInputElement>('input[type="text"]')!;
    const errorId = input.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    const errorEl = host.querySelector(`#${errorId}`);
    expect(errorEl?.getAttribute('role')).toBe('alert');
  });

  it('Enter submits the form when valid', async () => {
    const field = { key: 'name', type: 'string' as const, required: true };
    await setForm({ id: 'frm_1', sessionID: 's1', title: 'Q', fields: [field] });

    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));

    component.onStringInput(field, 'Jo');
    fixture.detectChanges();
    component.onKeydown(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(emitted).toEqual({ name: 'Jo' });
  });

  it('Enter does NOT submit when the form is invalid', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [{ key: 'name', type: 'string', required: true }]
    });

    let emitted: FormAnswer | undefined;
    component.submitForm.subscribe((a) => (emitted = a));

    component.onKeydown(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(emitted).toBeUndefined();
  });

  it('renders a numeric input for a number field', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [{ key: 'n', type: 'number', title: 'Count' }]
    });

    const numberInput = (fixture.nativeElement as HTMLElement).querySelector(
      'input[type="number"]'
    );
    expect(numberInput).not.toBeNull();
  });

  it('emits cancel when cancelled', async () => {
    await setForm({
      id: 'frm_1',
      sessionID: 's1',
      title: 'Q',
      fields: [{ key: 's', type: 'string' }]
    });

    let cancelled = false;
    component.cancelForm.subscribe(() => (cancelled = true));

    component.onCancel();

    expect(cancelled).toBe(true);
  });
});
