import { Injectable, inject } from '@angular/core';

import { Part } from '../models/opencode.models';
import { ChildSignals } from './agent-status';
import { FileRef, dedupeRefs, fileRefsFromMessageParts, fileRefsFromMessages, fileRefsFromText } from './file-refs';
import { LoadedMessagesService } from './loaded-messages.service';
import { isSubagentTool, subagentSessionId } from './part-utils';

@Injectable({ providedIn: 'root' })
export class FileRefsService {
  private readonly loaded = inject(LoadedMessagesService);

  refsForParts(parts: Part[], childSignals: ReadonlyMap<string, ChildSignals>): FileRef[] {
    const refs = fileRefsFromMessageParts(parts);
    for (const part of parts) {
      if (!isSubagentTool(part)) {
        continue;
      }
      const childId = subagentSessionId(part);
      if (!childId) {
        continue;
      }
      const messages = this.loaded.bySession().get(childId);
      if (messages) {
        refs.push(...fileRefsFromMessages(messages));
      }
      const body = childSignals.get(childId)?.result?.body;
      if (body) {
        refs.push(...fileRefsFromText(body));
      }
    }
    return dedupeRefs(refs);
  }
}
