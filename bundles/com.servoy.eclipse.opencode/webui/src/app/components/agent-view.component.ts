import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  input,
  output,
  viewChild
} from '@angular/core';

import { Session } from '../models/opencode.models';
import { ChildSignals } from '../services/agent-status';
import { buildAgentView } from '../services/agent-view';
import { ChatMessage, ChatStore } from '../services/chat-store.service';
import { fileRefsFromMessages } from '../services/file-refs';
import { isSubagentTool, toolDisplayName } from '../services/part-utils';
import { AgentReportComponent } from './agent-report.component';
import { AgentResumeDividerComponent } from './agent-resume-divider.component';
import { AgentTaskComponent } from './agent-task.component';
import { MessageItemComponent } from './message-item.component';
import { ThinkingIndicatorComponent } from './thinking-indicator.component';

interface RoundRow {
  readonly resume: string | null;
  readonly messages: ChatMessage[];
  readonly stoppedAtEnd: boolean;
  readonly runningTool: string | null;
  /** The child is working but nothing visible says so: the hidden reasoning stands in as "Thinking...". */
  readonly thinking: boolean;
}

@Component({
  selector: 'svy-agent-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AgentTaskComponent,
    AgentReportComponent,
    AgentResumeDividerComponent,
    MessageItemComponent,
    ThinkingIndicatorComponent
  ],
  templateUrl: './agent-view.component.html',
  styleUrl: './agent-view.component.scss'
})
export class AgentViewComponent {
  readonly messages = input<ChatMessage[]>([]);
  readonly session = input<Session | undefined>(undefined);
  readonly child = input<ChildSignals | undefined>(undefined);
  readonly parentTitle = input<string | null>(null);
  readonly parentId = input<string | null>(null);

  readonly openSession = output<string>();

  readonly scroll = viewChild.required<ElementRef<HTMLDivElement>>('scroll');

  readonly view = computed(() => buildAgentView(this.messages(), this.session(), this.child()));
  readonly busy = computed(() => this.view().status.status === 'running');
  readonly files = computed(() => fileRefsFromMessages(this.messages()));

  private readonly store = inject(ChatStore);
  readonly childSignals = this.store.childSignals;

  readonly rows = computed<RoundRow[]>(() => {
    const infoById = new Map(this.messages().map((m) => [m.info.id, m.info]));
    const rounds = this.view().rounds;
    return rounds.map((round, roundIndex) => {
      const grouped: ChatMessage[] = [];
      let runningTool: string | null = null;
      for (const step of round.steps) {
        if (step.running) {
          runningTool = toolDisplayName(step.part);
        }
        const last = grouped[grouped.length - 1];
        if (last && last.info.id === step.messageId) {
          last.parts.push(step.part);
        } else {
          const info = infoById.get(step.messageId);
          if (info) {
            grouped.push({ info, parts: [step.part] });
          }
        }
      }
      const lastStep = round.steps[round.steps.length - 1];
      const thinking =
        roundIndex === rounds.length - 1 &&
        this.busy() &&
        !round.stoppedAtEnd &&
        runningTool === null &&
        lastStep?.kind !== 'text' &&
        !(lastStep && isSubagentTool(lastStep.part));
      return { resume: round.resume, messages: grouped, stoppedAtEnd: round.stoppedAtEnd, runningTool, thinking };
    });
  });

  private pinnedToBottom = true;
  private static readonly BOTTOM_PIN_PX = 80;

  constructor() {
    effect(() => {
      void this.session()?.id;
      this.pinnedToBottom = true;
    });
    afterRenderEffect(() => {
      this.view();
      const el = this.scroll().nativeElement;
      if (this.busy() && this.pinnedToBottom) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  onScroll(): void {
    const el = this.scroll().nativeElement;
    this.pinnedToBottom =
      el.scrollHeight - el.scrollTop - el.clientHeight < AgentViewComponent.BOTTOM_PIN_PX;
  }
}
