import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CommandInfo } from '../models/opencode.models';
import { CommandCatalogService } from './command-catalog.service';
import { OpencodeApiService } from './opencode-api.service';

describe('CommandCatalogService', () => {
  let listCommands: ReturnType<typeof vi.fn>;
  let service: CommandCatalogService;

  function setup(impl: () => unknown): void {
    listCommands = vi.fn(impl);
    TestBed.configureTestingModule({
      providers: [{ provide: OpencodeApiService, useValue: { listCommands } }]
    });
    service = TestBed.inject(CommandCatalogService);
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('starts empty and does not fetch until load()', () => {
    setup(() => of([]));
    expect(service.commands()).toEqual([]);
    expect(listCommands).not.toHaveBeenCalled();
  });

  it('load() populates only svy commands', () => {
    setup(() =>
      of<CommandInfo[]>([
        { name: 'init', description: 'x' },
        { name: 'svyFixTicket', description: 'Fix  ticket' }
      ])
    );
    service.load();
    expect(service.commands()).toEqual([{ name: 'svyFixTicket', description: 'Fix ticket' }]);
  });

  it('does not refetch after a successful load', () => {
    setup(() => of<CommandInfo[]>([{ name: 'svyA' }]));
    service.load();
    service.load();
    expect(listCommands).toHaveBeenCalledTimes(1);
  });

  it('concurrent load() calls issue a single request', () => {
    const subject = new Subject<CommandInfo[]>();
    setup(() => subject);
    service.load();
    service.load();
    expect(listCommands).toHaveBeenCalledTimes(1);
    subject.next([{ name: 'svyA' }]);
    subject.complete();
    expect(service.commands().map((c) => c.name)).toEqual(['svyA']);
  });

  it('stays empty on error and retries on the next load()', () => {
    let fail = true;
    setup(() => (fail ? throwError(() => new Error('down')) : of<CommandInfo[]>([{ name: 'svyA' }])));
    service.load();
    expect(service.commands()).toEqual([]);
    fail = false;
    service.load();
    expect(listCommands).toHaveBeenCalledTimes(2);
    expect(service.commands().map((c) => c.name)).toEqual(['svyA']);
  });

  describe('start-up retry', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('retries after an empty answer until svy commands show up', () => {
      let ready = false;
      setup(() => of<CommandInfo[]>(ready ? [{ name: 'svyA' }] : [{ name: 'init' }]));
      service.load();
      expect(service.commands()).toEqual([]);
      vi.advanceTimersByTime(CommandCatalogService.RETRY_DELAY_MS);
      expect(listCommands).toHaveBeenCalledTimes(2);
      expect(service.commands()).toEqual([]);
      ready = true;
      vi.advanceTimersByTime(CommandCatalogService.RETRY_DELAY_MS);
      expect(listCommands).toHaveBeenCalledTimes(3);
      expect(service.commands().map((c) => c.name)).toEqual(['svyA']);
    });

    it('retries by itself after an error', () => {
      let fail = true;
      setup(() => (fail ? throwError(() => new Error('down')) : of<CommandInfo[]>([{ name: 'svyA' }])));
      service.load();
      fail = false;
      vi.advanceTimersByTime(CommandCatalogService.RETRY_DELAY_MS);
      expect(service.commands().map((c) => c.name)).toEqual(['svyA']);
    });

    it('stops retrying once commands were loaded', () => {
      setup(() => of<CommandInfo[]>([{ name: 'svyA' }]));
      service.load();
      vi.advanceTimersByTime(CommandCatalogService.RETRY_DELAY_MS * 5);
      expect(listCommands).toHaveBeenCalledTimes(1);
    });

    it('gives up after MAX_RETRIES', () => {
      setup(() => of<CommandInfo[]>([]));
      service.load();
      vi.advanceTimersByTime(CommandCatalogService.RETRY_DELAY_MS * (CommandCatalogService.MAX_RETRIES + 10));
      expect(listCommands).toHaveBeenCalledTimes(CommandCatalogService.MAX_RETRIES + 1);
    });

    it('does not retry after the service is destroyed', () => {
      setup(() => of<CommandInfo[]>([]));
      service.load();
      TestBed.resetTestingModule();
      vi.advanceTimersByTime(CommandCatalogService.RETRY_DELAY_MS * 3);
      expect(listCommands).toHaveBeenCalledTimes(1);
    });
  });
});
