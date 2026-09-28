import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';

import { FormsModule } from '@angular/forms';

import {
  FormAnswer,
  FormField,
  FormInfo,
  FormMultiselectField,
  FormOption,
  FormValue
} from '../models/opencode.models';
import { buildFormAnswer, evaluateForm, initialAnswer } from '../services/form-state';

/**
 * Renders an opencode V2 interactive form (the {@code form.created} exchange -
 * what an agent's question tool produces) as an inline card in the transcript,
 * with typed inputs per field. Submit is disabled until every visible required
 * field is valid; on submit it emits the settled answer map (keyed by field
 * key) for the store to POST to {@code .../reply}. Cancel emits so the store can
 * cancel the form (aborting the waiting turn).
 *
 * Standalone, {@code OnPush}, signals only - matches the rest of the chat UI.
 */
@Component({
  selector: 'svy-form-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './form-card.component.html',
  styleUrl: './form-card.component.scss'
})
export class FormCardComponent {
  readonly form = input.required<FormInfo>();

  readonly submitForm = output<FormAnswer>();
  readonly cancelForm = output<void>();

  /** The card root, used to move focus to the first control on render. */
  private readonly card = viewChild.required<ElementRef<HTMLElement>>('card');

  /** Working answer map, seeded from field defaults when the form is set. */
  private readonly answerSig = signal<FormAnswer>({});

  /** Draft free-text entries per multiselect field key (the "add your own" box). */
  private readonly customDrafts = signal<Record<string, string>>({});

  /** Tracks which form id the working answer was seeded for. */
  private seededFormId: string | null = null;

  /** Live evaluation: visible fields, per-field errors, overall validity. */
  readonly evaluation = computed(() => evaluateForm(this.form(), this.answerSig()));

  readonly answer = this.answerSig.asReadonly();

  constructor() {
    // Seed the working answer whenever the bound form changes. Done in an effect
    // (not the computed) because a computed must not write to a signal (NG0600).
    effect(() => {
      const form = this.form();
      if (this.seededFormId === form.id) {
        return;
      }
      this.seededFormId = form.id;
      this.answerSig.set(initialAnswer(form));
      this.customDrafts.set({});
    });
    // Move keyboard focus to the first focusable control when the card renders.
    afterNextRender(() => {
      const first = this.card().nativeElement.querySelector<HTMLElement>(
        'input, button.option, textarea, a.external-link'
      );
      first?.focus();
    });
  }

  // --- per-field value accessors used by the template ---

  stringValue(field: FormField): string {
    const v = this.answerSig()[field.key];
    return typeof v === 'string' ? v : '';
  }

  numberValue(field: FormField): number | null {
    const v = this.answerSig()[field.key];
    return typeof v === 'number' ? v : v != null ? Number(v) : null;
  }

  booleanValue(field: FormField): boolean {
    return this.answerSig()[field.key] === true;
  }

  isOptionSelected(field: FormField, option: FormOption): boolean {
    return this.answerSig()[field.key] === option.value;
  }

  isMultiSelected(field: FormField, option: FormOption): boolean {
    const v = this.answerSig()[field.key];
    return Array.isArray(v) && v.includes(option.value);
  }

  fieldError(field: FormField): string | null {
    return this.evaluation().errors[field.key] ?? null;
  }

  /** A stable DOM id for a field's control (for label {@code for} / aria). */
  controlId(field: FormField): string {
    return `svy-form-${this.form().id}-${field.key}`;
  }

  errorId(field: FormField): string {
    return `${this.controlId(field)}-error`;
  }

  // --- per-field mutators ---

  setValue(field: FormField, value: FormValue): void {
    this.answerSig.update((a) => ({ ...a, [field.key]: value }));
  }

  onStringInput(field: FormField, value: string): void {
    this.setValue(field, value);
  }

  onNumberInput(field: FormField, value: string): void {
    this.setValue(field, value === '' ? '' : Number(value));
  }

  onBooleanToggle(field: FormField, checked: boolean): void {
    this.setValue(field, checked);
  }

  selectOption(field: FormField, option: FormOption): void {
    this.setValue(field, option.value);
  }

  toggleMultiOption(field: FormMultiselectField, option: FormOption, checked: boolean): void {
    this.toggleMultiValue(field, option.value, checked);
  }

  /** Adds/removes a raw value in a multiselect answer array. */
  private toggleMultiValue(field: FormMultiselectField, value: string, checked: boolean): void {
    this.answerSig.update((a) => {
      const current = Array.isArray(a[field.key]) ? (a[field.key] as string[]) : [];
      const next = checked
        ? current.includes(value)
          ? current
          : [...current, value]
        : current.filter((v) => v !== value);
      return { ...a, [field.key]: next };
    });
  }

  /** The current draft text of a multiselect field's "add your own" box. */
  customDraft(field: FormField): string {
    return this.customDrafts()[field.key] ?? '';
  }

  onCustomDraftInput(field: FormField, value: string): void {
    this.customDrafts.update((d) => ({ ...d, [field.key]: value }));
  }

  /**
   * Commits the "add your own" draft as a selected value on a {@code custom}
   * multiselect, then clears the draft. No-op for a blank/duplicate entry.
   */
  addCustomValue(field: FormMultiselectField): void {
    const draft = this.customDraft(field).trim();
    if (!draft) {
      return;
    }
    this.toggleMultiValue(field, draft, true);
    this.customDrafts.update((d) => ({ ...d, [field.key]: '' }));
  }

  /**
   * Whether a selected multiselect value is a custom (user-typed) entry, i.e.
   * not one of the field's predefined options. Used to render removable chips.
   */
  customSelections(field: FormMultiselectField): string[] {
    const selected = this.answerSig()[field.key];
    if (!Array.isArray(selected)) {
      return [];
    }
    const optionValues = new Set((field.options ?? []).map((o) => o.value));
    return selected.filter((v) => !optionValues.has(v));
  }

  removeCustomValue(field: FormMultiselectField, value: string): void {
    this.toggleMultiValue(field, value, false);
  }

  acknowledgeExternal(field: FormField): void {
    this.setValue(field, true);
  }

  isAcknowledged(field: FormField): boolean {
    return this.answerSig()[field.key] === true;
  }

  // --- actions ---

  onSubmit(): void {
    const form = this.form();
    if (!this.evaluation().valid) {
      return;
    }
    this.submitForm.emit(buildFormAnswer(form, this.answerSig()));
  }

  onCancel(): void {
    this.cancelForm.emit();
  }

  /** Enter submits the whole form when valid (Shift+Enter is left alone). */
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      const target = event.target as HTMLElement | null;
      // Don't hijack Enter inside a multiline control.
      if (target?.tagName === 'TEXTAREA') {
        return;
      }
      event.preventDefault();
      this.onSubmit();
    }
  }

  fieldKey(_index: number, field: FormField): string {
    return field.key || String(_index);
  }

  optionKey(_index: number, option: FormOption): string {
    return option.value;
  }
}
