import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';

import { AppComponent } from './app/app.component';

const appConfig: ApplicationConfig = {
  providers: [provideZonelessChangeDetection(), provideHttpClient(withFetch())]
};

bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
