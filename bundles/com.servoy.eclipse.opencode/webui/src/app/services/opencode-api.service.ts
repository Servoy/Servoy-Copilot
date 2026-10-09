import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import {
  FileMatch,
  FormAnswer,
  FormInfo,
  MessageWithParts,
  SendPart,
  Session
} from '../models/opencode.models';
import { mapV2Message } from './v2-mapping';

/**
 * Typed wrappers over the BFF servlet's {@code ./rest_api/**} endpoints, which
 * proxy the opencode (V2, {@code @opencode/cli} 2.x) HTTP API under
 * {@code /api/**} (the servlet rewrites {@code rest_api/x} to {@code /api/x} and
 * injects the project directory + basic-auth server-side).
 *
 * V2 wraps every response in an envelope ({@code { data }} or
 * {@code { location, data }}); these wrappers unwrap it so callers keep seeing
 * plain values. All calls are relative to the Angular base href
 * ({@code /servoy_ai/}) so the app is location-independent.
 */
@Injectable({ providedIn: 'root' })
export class OpencodeApiService {
  private readonly http = inject(HttpClient);

  /** Base for all API calls, relative to the app base href. */
  private readonly base = 'rest_api';

  /** List root + subagent sessions (V2 {@code GET /session} -> {@code { data }}). */
  listSessions(): Observable<Session[]> {
    return this.http
      .get<{ data?: Session[] }>(`${this.base}/session`)
      .pipe(map((r) => r?.data ?? []));
  }

  /** Create a session (V2 {@code POST /session} -> {@code { data }}). */
  createSession(title?: string): Observable<Session> {
    return this.http
      .post<{ data: Session }>(`${this.base}/session`, title ? { title } : {})
      .pipe(map((r) => r.data));
  }

  /** Fetch one session (V2 {@code GET /session/:id} -> {@code { data }}). */
  getSession(id: string): Observable<Session> {
    return this.http
      .get<{ data: Session }>(`${this.base}/session/${encodeURIComponent(id)}`)
      .pipe(map((r) => r.data));
  }

  /**
   * Rename a session (V2 {@code PATCH /session/:id}, body {@code { title }}).
   * V2 returns 204 No Content, so this resolves to {@code void}.
   */
  updateSessionTitle(id: string, title: string): Observable<void> {
    return this.http.patch<void>(`${this.base}/session/${encodeURIComponent(id)}`, { title });
  }

  /**
   * Archive a session by stamping {@code time.archived} (V2
   * {@code PATCH /session/:id}, 204). Archived sessions are dropped from
   * {@code GET /session} server-side, so a list refresh hides them.
   */
  archiveSession(id: string, archivedAt: number = Date.now()): Observable<void> {
    return this.http.patch<void>(`${this.base}/session/${encodeURIComponent(id)}`, {
      time: { archived: archivedAt }
    });
  }

  /** Permanently delete a session (V2 {@code DELETE /session/:id}, 204). */
  deleteSession(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/session/${encodeURIComponent(id)}`);
  }

  /**
   * List a session's messages (V2 {@code GET /session/:id/message} ->
   * {@code { data, cursor }}). Each raw V2 message is normalised into the
   * {@link MessageWithParts} shape the store renders. V2 returns newest-first
   * (desc); the store expects chronological order, so we reverse.
   */
  listMessages(id: string, limit?: number): Observable<MessageWithParts[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', String(limit));
    }
    return this.http
      .get<{ data?: unknown[] }>(`${this.base}/session/${encodeURIComponent(id)}/message`, {
        params
      })
      .pipe(map((r) => (r?.data ?? []).map(mapV2Message).reverse()));
  }

  /**
   * Send a prompt (V2 {@code POST /session/:id/prompt}, body {@code { text }}).
   * V2 has a single {@code text} field rather than a {@code parts} array; the
   * text parts are concatenated. Streaming continues on {@code /event}.
   * <p>
   * Each attachment is a {@code PromptInput.FileAttachment}:
   * {@code { uri, name? }}. opencode takes the content from {@code uri} (a
   * {@code data:} URL for an inline/pasted image, or a {@code file:} URL / path
   * reference) and detects the mime type itself - there is no {@code mime} or
   * {@code filename} input field. (opencode 2.0.26 renamed the field to
   * {@code uri}; sending the old {@code url}/{@code filename}/{@code mime} shape
   * is rejected with "Missing key ... uri".)
   */
  sendPrompt(id: string, parts: SendPart[]): Observable<void> {
    const text = parts
      .filter((p) => p.type === 'text' && p.text)
      .map((p) => p.text)
      .join('\n');
    const files = parts
      .filter((p) => p.type === 'file' && p.url)
      .map((p) => {
        const file: { uri: string; name?: string } = { uri: p.url as string };
        if (p.filename) {
          file.name = p.filename;
        }
        return file;
      });
    const body: Record<string, unknown> = { text };
    if (files.length > 0) {
      body['files'] = files;
    }
    return this.http.post<void>(
      `${this.base}/session/${encodeURIComponent(id)}/prompt`,
      body
    );
  }

  /**
   * Interrupt the running turn (V2 {@code POST /session/:id/interrupt},
   * replacing V1's {@code /abort}).
   */
  interrupt(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/session/${encodeURIComponent(id)}/interrupt`, {});
  }

  /** Search files (V2 {@code GET /fs/find?query=} -> {@code { location, data }}). */
  findFiles(query: string): Observable<FileMatch[]> {
    const params = new HttpParams().set('query', query);
    return this.http
      .get<{ data?: FileMatch[] }>(`${this.base}/fs/find`, { params })
      .pipe(map((r) => r?.data ?? []));
  }

  /**
   * Read a file's text (V2 {@code GET /fs/read/<path>}). The path is a URL
   * path segment, not a query param, in V2.
   */
  readFile(path: string): Observable<string> {
    const encoded = path.split('/').map((seg) => encodeURIComponent(seg)).join('/');
    return this.http.get(`${this.base}/fs/read/${encoded}`, {
      responseType: 'text'
    });
  }

  /**
   * List a session's pending interactive forms (V2
   * {@code GET /session/:id/form} -> {@code { data }}). Used to recover a form
   * that is still open after a reload, since {@code form.created} may have been
   * missed while the page was gone.
   */
  listPendingForms(sessionID: string): Observable<FormInfo[]> {
    return this.http
      .get<{ data?: FormInfo[] }>(`${this.base}/session/${encodeURIComponent(sessionID)}/form`)
      .pipe(map((r) => r?.data ?? []));
  }

  /**
   * Answer a pending form (V2 {@code POST /session/:id/form/:formID/reply}, body
   * {@code { answer }}). The answer is keyed by each field's {@code key}. V2
   * returns 204 No Content, so this resolves to {@code void}; answering unblocks
   * the waiting agent turn.
   */
  replyToForm(sessionID: string, formID: string, answer: FormAnswer): Observable<void> {
    return this.http.post<void>(
      `${this.base}/session/${encodeURIComponent(sessionID)}/form/${encodeURIComponent(formID)}/reply`,
      { answer }
    );
  }

  /**
   * Cancel a pending form (V2 {@code DELETE /session/:id/form/:formID}, 204).
   * Cancelling aborts the waiting turn.
   */
  cancelForm(sessionID: string, formID: string): Observable<void> {
    return this.http.delete<void>(
      `${this.base}/session/${encodeURIComponent(sessionID)}/form/${encodeURIComponent(formID)}`
    );
  }
}
