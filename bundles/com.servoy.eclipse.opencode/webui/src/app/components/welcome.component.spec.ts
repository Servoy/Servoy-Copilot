import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CommandCatalogService } from '../services/command-catalog.service';
import { SvyCommand } from '../services/svy-commands';
import { WelcomeComponent } from './welcome.component';

describe('WelcomeComponent', () => {
  let fixture: ComponentFixture<WelcomeComponent>;
  let component: WelcomeComponent;
  const commands = signal<SvyCommand[]>([]);
  const load = vi.fn();

  const catalog: SvyCommand[] = [
    { name: 'svyCreateE2E', description: 'Create an E2E test' },
    { name: 'svyOptimize', description: 'Optimize code' },
    { name: 'svyFixStacktrace', description: 'Fix a stacktrace' },
    { name: 'svyFixTicket', description: 'Fix a ticket' },
    { name: 'svyImplement', description: 'Implement a feature' }
  ];

  const el = (): HTMLElement => fixture.nativeElement;
  const text = (): string => el().textContent!.replace(/\s+/g, ' ').trim();

  async function render(hasSessions: boolean): Promise<void> {
    fixture.componentRef.setInput('hasSessions', hasSessions);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    commands.set(catalog);
    load.mockClear();
    await TestBed.configureTestingModule({
      imports: [WelcomeComponent],
      providers: [provideZonelessChangeDetection(), { provide: CommandCatalogService, useValue: { commands, load } }]
    }).compileComponents();
    fixture = TestBed.createComponent(WelcomeComponent);
    component = fixture.componentInstance;
  });

  it('loads the command catalog on init', async () => {
    await render(false);
    expect(load).toHaveBeenCalledTimes(1);
  });

  describe('intro (no sessions)', () => {
    beforeEach(async () => render(false));

    it('shows the title and intro sentence', () => {
      expect(el().querySelector('h1.title')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Welcome to Servoy AI');
      expect(el().querySelector('.accent')!.textContent).toBe('AI');
      expect(el().querySelector('p.intro')!.textContent).toBe(
        'Describe what you need in your solution. A team of agents works it out - you only answer the questions that matter.'
      );
    });

    it('shows the three steps with their labels', () => {
      const labels = Array.from(el().querySelectorAll('ol.steps li .label')).map((l) => l.textContent!.trim());
      expect(labels).toEqual(['01 - You', '02 - Orchestrator', '03 - Agents']);
      expect(el().querySelectorAll('ol.steps li')).toHaveLength(3);
    });

    it('shows the three steps with their titles and descriptions', () => {
      const titles = Array.from(el().querySelectorAll('ol.steps li .step-title')).map((t) => t.textContent!.trim());
      const texts = Array.from(el().querySelectorAll('ol.steps li .step-text')).map((t) => t.textContent!.trim());
      expect(titles).toEqual(['Ask in plain words', 'Plans, delegates, asks', 'Do one task, report back']);
      expect(texts).toEqual([
        'Type, paste a screenshot, or pick a / command.',
        'The only one who talks to you. It asks when a choice is yours.',
        'Each agent finishes or stops and hands back. Open one to see its work.'
      ]);
    });

    it('shows "Try one" and one card per starter in STARTER_NAMES order with catalog descriptions', () => {
      expect(el().querySelector('h2.try')!.textContent).toBe('Try one');
      const cards = Array.from(el().querySelectorAll('button.card'));
      expect(cards.map((c) => c.querySelector('.cmd')!.textContent)).toEqual([
        '/svyImplement',
        '/svyFixTicket',
        '/svyFixStacktrace',
        '/svyCreateE2E'
      ]);
      expect(cards.map((c) => c.querySelector('.desc')!.textContent)).toEqual([
        'Implement a feature',
        'Fix a ticket',
        'Fix a stacktrace',
        'Create an E2E test'
      ]);
    });

    it('does not render non-starter commands', () => {
      expect(text()).not.toContain('svyOptimize');
    });

    it('renders no pills and not the hero line', () => {
      expect(el().querySelector('.pill')).toBeNull();
      expect(text()).not.toContain('What should we build');
    });

    it('uses native buttons', () => {
      const cards = Array.from(el().querySelectorAll('button.card'));
      expect(cards).toHaveLength(4);
      expect(cards.every((c) => c.getAttribute('type') === 'button')).toBe(true);
    });

    it('omits a starter missing from the catalog', async () => {
      commands.set(catalog.filter((c) => c.name !== 'svyFixTicket'));
      fixture.detectChanges();
      const names = Array.from(el().querySelectorAll('button.card .cmd')).map((c) => c.textContent);
      expect(names).toEqual(['/svyImplement', '/svyFixStacktrace', '/svyCreateE2E']);
    });

    it('renders no starters and no "Try one" for an empty catalog but keeps title, intro and steps', async () => {
      commands.set([]);
      fixture.detectChanges();
      expect(el().querySelector('button.card')).toBeNull();
      expect(el().querySelector('h2.try')).toBeNull();
      expect(el().querySelector('ul.cards')).toBeNull();
      expect(text()).toContain('Welcome to Servoy AI');
      expect(el().querySelector('p.intro')).not.toBeNull();
      expect(el().querySelectorAll('ol.steps li')).toHaveLength(3);
    });

    it('shows starters when the catalog arrives later', () => {
      commands.set([]);
      fixture.detectChanges();
      expect(el().querySelector('button.card')).toBeNull();
      commands.set(catalog);
      fixture.detectChanges();
      expect(el().querySelectorAll('button.card')).toHaveLength(4);
    });

    it('emits the exact command when a card is clicked', () => {
      const picked: SvyCommand[] = [];
      component.pick.subscribe((c) => picked.push(c));
      (el().querySelectorAll('button.card')[1] as HTMLButtonElement).click();
      expect(picked).toHaveLength(1);
      expect(picked[0]).toBe(catalog[3]);
    });
  });

  describe('hero (has sessions)', () => {
    beforeEach(async () => render(true));

    it('shows exactly the line "What should we build in Servoy today?"', () => {
      expect(el().querySelector('h1.title')!.textContent).toBe('What should we build in Servoy today?');
      expect(el().querySelector('.accent')!.textContent).toBe('Servoy');
    });

    it('shows pills with /name only and no description text', () => {
      const pills = Array.from(el().querySelectorAll('button.pill'));
      expect(pills.map((p) => p.textContent!.trim())).toEqual([
        '/svyImplement',
        '/svyFixTicket',
        '/svyFixStacktrace',
        '/svyCreateE2E'
      ]);
      expect(text()).not.toContain('Implement a feature');
      expect(text()).not.toContain('Fix a ticket');
    });

    it('exposes the description as pill title', () => {
      const pill = el().querySelector('button.pill') as HTMLButtonElement;
      expect(pill.getAttribute('title')).toBe('Implement a feature');
    });

    it('has no title attribute for a starter without description', () => {
      commands.set([{ name: 'svyImplement', description: '' }]);
      fixture.detectChanges();
      expect(el().querySelector('button.pill')!.hasAttribute('title')).toBe(false);
    });

    it('has no steps, no "Try one" and no cards', () => {
      expect(el().querySelector('ol.steps')).toBeNull();
      expect(el().querySelector('h2.try')).toBeNull();
      expect(el().querySelector('button.card')).toBeNull();
      expect(text()).not.toContain('Welcome to Servoy');
    });

    it('omits a missing starter', () => {
      commands.set(catalog.filter((c) => c.name !== 'svyImplement'));
      fixture.detectChanges();
      const names = Array.from(el().querySelectorAll('button.pill')).map((c) => c.textContent!.trim());
      expect(names).toEqual(['/svyFixTicket', '/svyFixStacktrace', '/svyCreateE2E']);
    });

    it('keeps the line but renders no pills for an empty catalog', () => {
      commands.set([]);
      fixture.detectChanges();
      expect(el().querySelector('ul.pills')).toBeNull();
      expect(el().querySelector('button.pill')).toBeNull();
      expect(text()).toBe('What should we build in Servoy today?');
    });

    it('emits the exact command when a pill is clicked', () => {
      const picked: SvyCommand[] = [];
      component.pick.subscribe((c) => picked.push(c));
      (el().querySelectorAll('button.pill')[3] as HTMLButtonElement).click();
      expect(picked).toHaveLength(1);
      expect(picked[0]).toBe(catalog[0]);
    });
  });

  describe('reactivity', () => {
    it('switches between intro and hero when hasSessions changes', async () => {
      await render(false);
      expect(el().querySelector('ol.steps')).not.toBeNull();
      expect(el().querySelector('button.pill')).toBeNull();
      await render(true);
      expect(el().querySelector('ol.steps')).toBeNull();
      expect(el().querySelectorAll('button.pill')).toHaveLength(4);
    });

    it('does not throw NG0600 when inputs and commands change', async () => {
      await render(false);
      expect(() => {
        fixture.componentRef.setInput('hasSessions', true);
        commands.set([]);
        fixture.detectChanges();
        commands.set(catalog);
        fixture.detectChanges();
      }).not.toThrow();
    });
  });

  it('has a pick output and no send output', () => {
    expect(component.pick).toBeDefined();
    expect('send' in component).toBe(false);
    expect('sendMessage' in component).toBe(false);
  });
});
