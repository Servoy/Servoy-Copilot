import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  FileMatch,
  MessageWithParts,
  SendPart,
  Session
} from '../models/opencode.models';

/**
 * Typed wrappers over the BFF servlet's {@code ./rest_api/**} endpoints.
 *
 * All calls are relative to the Angular base href ({@code /servoy_ai/}) so the
 * app is location-independent; the servlet injects the project directory
 * server-side, so no directory argument is ever passed from here.
 */
@Injectable({ providedIn: 'root' })
export class OpencodeApiService {
  private readonly http = inject(HttpClient);

  /** Base for all API calls, relative to the app base href. */
  private readonly base = 'rest_api';

  listSessions(): Observable<Session[]> {
    return this.http.get<Session[]>(`${this.base}/session`);
  }

  createSession(title?: string): Observable<Session> {
    return this.http.post<Session>(`${this.base}/session`, title ? { title } : {});
  }

  getSession(id: string): Observable<Session> {
    return this.http.get<Session>(`${this.base}/session/${encodeURIComponent(id)}`);
  }

  /** Rename a session (opencode {@code PATCH /session/:id}, body {@code { title }}). */
  updateSessionTitle(id: string, title: string): Observable<Session> {
    return this.http.patch<Session>(`${this.base}/session/${encodeURIComponent(id)}`, { title });
  }

  /**
   * Archive a session by stamping {@code time.archived} (opencode
   * {@code PATCH /session/:id}). Archived sessions are dropped from
   * {@code GET /session} server-side, so a list refresh hides them.
   */
  archiveSession(id: string, archivedAt: number = Date.now()): Observable<Session> {
    return this.http.patch<Session>(`${this.base}/session/${encodeURIComponent(id)}`, {
      time: { archived: archivedAt }
    });
  }

  /** Permanently delete a session and all its data (opencode {@code DELETE /session/:id}). */
  deleteSession(id: string): Observable<boolean> {
    return this.http.delete<boolean>(`${this.base}/session/${encodeURIComponent(id)}`);
  }

  listMessages(id: string, limit?: number): Observable<MessageWithParts[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', String(limit));
    }
    return this.http.get<MessageWithParts[]>(
      `${this.base}/session/${encodeURIComponent(id)}/message`,
      { params }
    );
  }

  /** Send a prompt without waiting for the full response (streams on /event). */
  sendPromptAsync(id: string, parts: SendPart[]): Observable<void> {
    return this.http.post<void>(
      `${this.base}/session/${encodeURIComponent(id)}/prompt_async`,
      { parts }
    );
  }

  abort(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/session/${encodeURIComponent(id)}/abort`, {});
  }

  findFiles(query: string): Observable<FileMatch[]> {
    const params = new HttpParams().set('query', query);
    return this.http.get<FileMatch[]>(`${this.base}/find/file`, { params });
  }

  readFile(path: string): Observable<string> {
    const params = new HttpParams().set('path', path);
    return this.http.get(`${this.base}/file/content`, {
      params,
      responseType: 'text'
    });
  }
}
