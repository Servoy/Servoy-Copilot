import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output
} from '@angular/core';

import { CommandCatalogService } from '../services/command-catalog.service';
import { starterCommands } from '../services/starters';
import { SvyCommand } from '../services/svy-commands';

@Component({
  selector: 'svy-welcome',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './welcome.component.html',
  styleUrl: './welcome.component.scss'
})
export class WelcomeComponent implements OnInit {
  readonly hasSessions = input(false);
  readonly pick = output<SvyCommand>();

  private readonly catalog = inject(CommandCatalogService);
  readonly starters = computed(() => starterCommands(this.catalog.commands()));

  ngOnInit(): void {
    this.catalog.load();
  }
}
